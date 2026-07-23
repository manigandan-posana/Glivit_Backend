package com.glivt.ai.repository;

import com.glivt.ai.entity.AiPromptVersion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiPromptVersionRepository extends JpaRepository<AiPromptVersion, Long> {

    Optional<AiPromptVersion> findByPromptKeyAndVersion(String promptKey, String version);

    Optional<AiPromptVersion> findFirstByPromptKeyAndActiveTrueOrderByCreatedAtDesc(String promptKey);
}
