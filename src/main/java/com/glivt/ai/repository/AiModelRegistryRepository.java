package com.glivt.ai.repository;

import com.glivt.ai.entity.AiModelRegistry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiModelRegistryRepository extends JpaRepository<AiModelRegistry, Long> {

    List<AiModelRegistry> findByModelName(String modelName);

    Optional<AiModelRegistry> findFirstByModelNameAndStatusOrderByUpdatedAtDesc(String modelName, String status);
}
