package com.glivt.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.glivt.ai.tools.AiTool;
import com.glivt.ai.tools.AiToolRegistry;
import com.glivt.security.AppUserPrincipal;
import com.glivt.security.PermissionKeys;
import com.glivt.security.Permissions;
import com.glivt.user.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The tool layer is what replaced keyword-based intent matching, so these tests
 * guard the two properties that make it safe: the model can only ever be offered
 * tools the user's permissions allow, and executing one it was not offered still
 * fails closed.
 */
@SpringBootTest
class AiToolRegistryTest {

    private static final Long TENANT = 1L;

    @Autowired private AiToolRegistry registry;

    private AppUserPrincipal userWith(Role role, String permissionsJson) {
        return new AppUserPrincipal(9L, TENANT, TENANT, "user", role, true,
                Permissions.forUser(role, permissionsJson));
    }

    private AppUserPrincipal admin() {
        return userWith(Role.ADMIN, null);
    }

    /**
     * A minimal-privilege user. DRIVER defaults to live location only, which is
     * the tightest real role in the product.
     */
    private AppUserPrincipal viewerOnly() {
        return userWith(Role.DRIVER, null);
    }

    private List<String> toolNames(AppUserPrincipal user) {
        return registry.schemasFor(user).stream()
                .map(schema -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> function = (Map<String, Object>) schema.get("function");
                    return String.valueOf(function.get("name"));
                })
                .toList();
    }

    @Test
    void advertisesTheCoreFleetToolsToAnAdmin() {
        assertThat(toolNames(admin())).contains(
                "get_fleet_status", "list_vehicles", "get_vehicle", "list_live_positions",
                "list_devices", "list_drivers", "get_driver_scores", "list_alerts",
                "list_geofences", "list_maintenance_predictions", "list_trips",
                "list_reports", "list_users", "get_app_capabilities");
    }

    @Test
    void schemasAreValidOllamaToolDefinitions() {
        for (Map<String, Object> schema : registry.schemasFor(admin())) {
            assertThat(schema).containsEntry("type", "function");
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) schema.get("function");
            assertThat(function.get("name")).asString().isNotBlank();
            // The description is the only thing the model matches on, so it has
            // to be substantive rather than a placeholder.
            assertThat(function.get("description")).asString().hasSizeGreaterThan(30);
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
            assertThat(parameters).containsEntry("type", "object");
            assertThat(parameters).containsKey("properties");
            assertThat(parameters).containsKey("required");
        }
    }

    @Test
    void aUserWithoutAPermissionIsNeverOfferedThatTool() {
        List<String> names = toolNames(viewerOnly());

        assertThat(names).contains("list_vehicles", "get_fleet_status");
        // Not advertised at all, so the model cannot even attempt them.
        assertThat(names).doesNotContain("list_users", "list_geofences", "list_reports",
                "list_groups", "list_projects");
        // An admin, by contrast, sees the management tools.
        assertThat(toolNames(admin())).contains("list_users", "list_geofences", "list_reports");
    }

    @Test
    void executingAToolWithoutPermissionFailsClosed() {
        // Simulates a model hallucinating a tool name it was never offered.
        Object result = registry.execute(viewerOnly(), "list_users", Map.of());

        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("error").toString()).contains("permission");
    }

    @Test
    void unknownToolNameIsRejectedRatherThanThrowing() {
        Object result = registry.execute(admin(), "drop_all_tables", Map.of());

        assertThat(((Map<?, ?>) result).get("error").toString()).contains("No such tool");
    }

    @Test
    void everyToolDeclaresAPermission() {
        for (String name : toolNames(admin())) {
            AiTool tool = registry.find(name).orElseThrow();
            assertThat(tool.requiredPermission())
                    .as("tool %s must declare a permission", name)
                    .isNotBlank();
        }
    }

    @Test
    void toolsRunAgainstAnEmptyTenantWithoutFailing() {
        // Tenant 1 has no seeded data in the test profile; every tool must return
        // an empty-but-valid result rather than throwing.
        for (String name : toolNames(admin())) {
            Object result = registry.execute(admin(), name, Map.of());
            assertThat(result).as("tool %s returned null", name).isNotNull();
        }
    }

    @Test
    void getVehicleRequiresItsArgumentAndReportsAMiss() {
        assertThat(((Map<?, ?>) registry.execute(admin(), "get_vehicle", Map.of())).get("error"))
                .asString().contains("Specify a vehicle");

        Object miss = registry.execute(admin(), "get_vehicle", Map.of("vehicle", "does-not-exist"));
        assertThat(((Map<?, ?>) miss).get("found")).isEqualTo(false);
    }

    @Test
    void emptyResultsCarryAnExplanatoryNoteRatherThanLookingLikeAnError() {
        // The model needs to be able to say "none yet" instead of "unavailable".
        Map<?, ?> scores = (Map<?, ?>) registry.execute(admin(), "get_driver_scores", Map.of());
        assertThat(scores.get("note")).asString().contains("nightly job");

        Map<?, ?> alerts = (Map<?, ?>) registry.execute(admin(), "list_alerts", Map.of());
        assertThat(alerts.get("note")).asString().contains("No AI alerts");
    }
}
