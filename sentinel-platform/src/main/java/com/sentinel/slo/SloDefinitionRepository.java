package com.sentinel.slo;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SloDefinitionRepository extends JpaRepository<SloDefinitionEntity, UUID> {

    List<SloDefinitionEntity> findByEnabledTrue();
}
