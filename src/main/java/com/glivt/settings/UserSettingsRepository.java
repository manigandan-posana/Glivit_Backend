package com.glivt.settings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {

    Optional<UserSettings> findByTenantIdAndUserId(Long tenantId, Long userId);
}
