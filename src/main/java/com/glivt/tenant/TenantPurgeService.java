package com.glivt.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes every row a tenant owns, in foreign-key-safe order.
 *
 * <p>Deleting a tenant has to remove the tenant's data, not just its {@code tenants}
 * row: leaving orphaned devices, positions or users behind would keep another
 * tenant's future record ids colliding with live foreign keys and would leave real
 * customer data on disk after an operator believed it was gone.
 *
 * <p>The statement list is native and explicit rather than JPA cascade-driven,
 * because several tenant-owned tables (payments, billing, notification rules,
 * report schedules, fuel readings) have no JPA entity in this codebase - a
 * cascade-based purge would silently skip exactly those. The order walks children
 * before parents so no foreign key is ever violated, and the whole purge runs in
 * one transaction so a failure leaves the tenant fully intact.
 *
 * <p>Tables absent from the running schema are skipped: the same code has to run
 * against the MySQL schema built by Flyway and the reduced entity-derived schema
 * used by the test profile.
 */
@Service
public class TenantPurgeService {

    private static final Logger log = LoggerFactory.getLogger(TenantPurgeService.class);

    /**
     * Tenant-owned tables, children first. Each entry is a table whose rows are
     * removed by matching {@code tenant_id}, unless a custom predicate is given
     * because the table is reachable only through a parent.
     */
    private record Step(String table, String whereClause) {

        static Step byTenant(String table) {
            return new Step(table, "tenant_id = :tenantId");
        }

        static Step of(String table, String whereClause) {
            return new Step(table, whereClause);
        }
    }

    private static final List<Step> STEPS = List.of(
            // --- AI layer -------------------------------------------------
            Step.byTenant("ai_feedback"),
            Step.byTenant("ai_event"),
            Step.byTenant("driver_score_daily"),
            Step.byTenant("trip_feature_snapshot"),
            Step.byTenant("maintenance_prediction"),
            Step.byTenant("geofence_suggestion"),
            Step.byTenant("dispatch_recommendation"),
            Step.byTenant("ai_model_registry"),

            // --- Operations ----------------------------------------------
            Step.byTenant("payments"),
            Step.byTenant("billing_transactions"),
            Step.byTenant("fuel_readings"),
            Step.byTenant("report_schedules"),
            Step.byTenant("reports"),
            Step.byTenant("device_commands"),
            Step.byTenant("notification_rules"),
            Step.byTenant("geofences"),
            Step.byTenant("events"),
            Step.byTenant("user_settings"),

            // --- Access mappings -----------------------------------------
            Step.byTenant("vehicle_driver_assignments"),
            Step.byTenant("user_project_assignments"),
            Step.byTenant("tenant_users"),

            // --- Telemetry (positions reference devices) ------------------
            Step.of("device_current_position", "tenant_id = :tenantId"),
            Step.byTenant("positions"),
            Step.byTenant("devices"),
            Step.byTenant("vehicles"),
            Step.byTenant("drivers"),
            Step.byTenant("device_groups"),
            Step.byTenant("projects"),
            Step.byTenant("tenant_telemetry_settings"),

            // --- Identity (refresh tokens reference users) -----------------
            Step.of("refresh_tokens",
                    "user_id in (select u.id from users u where u.tenant_id = :tenantId)"),
            // Manager self-reference must be cleared before the rows are removed.
            Step.of("users", "tenant_id = :tenantId"),

            // --- The tenant itself ----------------------------------------
            Step.of("tenants", "id = :tenantId"));

    @PersistenceContext
    private EntityManager entityManager;

    private final DataSource dataSource;

    public TenantPurgeService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Removes the tenant and everything it owns.
     *
     * <p>Audit rows are deliberately retained: the trail of who created, changed and
     * finally deleted a tenant must outlive the tenant. {@code audit_logs.tenant_id}
     * has no foreign key precisely so this is possible.
     *
     * @return the number of rows deleted, for the audit detail.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long purge(Long tenantId) {
        // Native statements bypass the persistence context, so anything still
        // pending must reach the database first or it would survive the purge.
        entityManager.flush();

        Set<String> present = existingTables();
        long deleted = 0;

        // Break the users.manager_id self-reference before deleting the rows.
        if (present.contains("users")) {
            deleted += entityManager
                    .createNativeQuery("update users set manager_id = null where tenant_id = :tenantId")
                    .setParameter("tenantId", tenantId)
                    .executeUpdate();
        }
        // A tenant row points at its provisioned admin; clear it before users go.
        if (present.contains("tenants")) {
            entityManager
                    .createNativeQuery("update tenants set admin_user_id = null where id = :tenantId")
                    .setParameter("tenantId", tenantId)
                    .executeUpdate();
        }
        // device_groups is hierarchical; flatten it so parents can be removed.
        if (present.contains("device_groups")) {
            entityManager
                    .createNativeQuery("update device_groups set parent_id = null where tenant_id = :tenantId")
                    .setParameter("tenantId", tenantId)
                    .executeUpdate();
        }
        // Vehicles and devices reference each other; drop the device side first.
        if (present.contains("vehicles")) {
            entityManager
                    .createNativeQuery("update vehicles set driver_id = null where tenant_id = :tenantId")
                    .setParameter("tenantId", tenantId)
                    .executeUpdate();
        }
        if (present.contains("devices")) {
            entityManager
                    .createNativeQuery(
                            "update devices set vehicle_id = null, manager_id = null where tenant_id = :tenantId")
                    .setParameter("tenantId", tenantId)
                    .executeUpdate();
        }

        for (Step step : STEPS) {
            if (!present.contains(step.table())) {
                log.debug("Skipping purge of absent table {}", step.table());
                continue;
            }
            int rows = entityManager
                    .createNativeQuery("delete from " + step.table() + " where " + step.whereClause())
                    .setParameter("tenantId", tenantId)
                    .executeUpdate();
            deleted += rows;
        }

        // Entities loaded before the purge are now phantom rows; drop them so any
        // later read in the same transaction goes back to the database.
        entityManager.clear();
        return deleted;
    }

    /** Lower-cased table names in the current schema, so absent tables are skipped. */
    private Set<String> existingTables() {
        Set<String> tables = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(connection.getCatalog(), null, "%",
                    new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to read schema metadata for tenant purge", ex);
        }
        return tables;
    }
}
