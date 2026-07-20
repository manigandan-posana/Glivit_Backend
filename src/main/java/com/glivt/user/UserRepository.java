package com.glivt.user;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByTenantIdAndUsernameIgnoreCase(Long tenantId, String username);

    Optional<User> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByTenantIdAndUsernameIgnoreCase(Long tenantId, String username);

    boolean existsByTenantIdAndUsernameIgnoreCaseAndIdNot(Long tenantId, String username, Long id);

    @Query("""
            select u from User u
            where u.tenantId = :tenantId
              and (:search is null
                   or lower(u.name) like lower(concat('%', :search, '%'))
                   or lower(u.username) like lower(concat('%', :search, '%'))
                   or lower(u.email) like lower(concat('%', :search, '%')))
            """)
    Page<User> search(@Param("tenantId") Long tenantId,
                      @Param("search") String search,
                      Pageable pageable);
}
