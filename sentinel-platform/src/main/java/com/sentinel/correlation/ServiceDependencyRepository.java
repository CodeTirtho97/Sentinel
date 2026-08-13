package com.sentinel.correlation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceDependencyRepository extends JpaRepository<ServiceDependency, ServiceDependency.Edge> {}
