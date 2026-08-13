package com.sentinel.correlation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** One directed edge of the static dependency graph: {@code serviceName} calls {@code dependsOn}. */
@Entity
@Table(name = "service_dependency")
public class ServiceDependency {

    @Embeddable
    public static class Edge implements Serializable {

        @Column(name = "service_name", nullable = false)
        private String serviceName;

        @Column(name = "depends_on", nullable = false)
        private String dependsOn;

        protected Edge() {}

        public Edge(String serviceName, String dependsOn) {
            this.serviceName = serviceName;
            this.dependsOn = dependsOn;
        }

        public String getServiceName() {
            return serviceName;
        }

        public String getDependsOn() {
            return dependsOn;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Edge other)) {
                return false;
            }
            return Objects.equals(serviceName, other.serviceName) && Objects.equals(dependsOn, other.dependsOn);
        }

        @Override
        public int hashCode() {
            return Objects.hash(serviceName, dependsOn);
        }
    }

    @EmbeddedId
    private Edge edge;

    protected ServiceDependency() {}

    public ServiceDependency(String serviceName, String dependsOn) {
        this.edge = new Edge(serviceName, dependsOn);
    }

    public String getServiceName() {
        return edge.getServiceName();
    }

    public String getDependsOn() {
        return edge.getDependsOn();
    }
}
