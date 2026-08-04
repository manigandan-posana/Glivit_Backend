package com.glivt.tenant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    @Query("SELECT t.id FROM Tenant t")
    List<Long> findAllIds();

    Optional<Tenant> findByCompanyCodeIgnoreCase(String companyCode);

    boolean existsByCompanyCodeIgnoreCase(String companyCode);

    boolean existsByCompanyCodeIgnoreCaseAndIdNot(String companyCode, Long id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByAdminEmailIgnoreCase(String adminEmail);

    boolean existsByAdminEmailIgnoreCaseAndIdNot(String adminEmail, Long id);

    /** Platform-wide tenant search (Super Admin only). */
    @Query("""
            select t from Tenant t
            where (:search is null
                   or lower(t.name) like lower(concat('%', :search, '%'))
                   or lower(t.companyCode) like lower(concat('%', :search, '%'))
                   or lower(t.companyName) like lower(concat('%', :search, '%'))
                   or lower(t.adminEmail) like lower(concat('%', :search, '%')))
            """)
    Page<Tenant> search(@Param("search") String search, Pageable pageable);

    /**
     * Search restricted to the tenants the caller is explicitly authorised for.
     * The id set is resolved from {@code tenant_users} server-side; it is never
     * taken from the request.
     */
    @Query("""
            select t from Tenant t
            where t.id in :tenantIds
              and (:search is null
                   or lower(t.name) like lower(concat('%', :search, '%'))
                   or lower(t.companyCode) like lower(concat('%', :search, '%'))
                   or lower(t.companyName) like lower(concat('%', :search, '%'))
                   or lower(t.adminEmail) like lower(concat('%', :search, '%')))
            """)
    Page<Tenant> searchWithin(@Param("tenantIds") Collection<Long> tenantIds,
                              @Param("search") String search,
                              Pageable pageable);

    List<Tenant> findByIdIn(Collection<Long> ids);
}
