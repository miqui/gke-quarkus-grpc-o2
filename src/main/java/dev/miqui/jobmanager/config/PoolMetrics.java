package dev.miqui.jobmanager.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@code db.pool.max} - the pool's configured capacity. Agroal's own metrics (agroal_*) report
 * active/available/awaiting connections but not the limit, which the "DB Connection Pool
 * Utilization" panel plots them against.
 */
@Startup
@ApplicationScoped
public class PoolMetrics {

    public PoolMetrics(MeterRegistry registry, @ConfigProperty(name = "quarkus.datasource.jdbc.max-size") int maxSize) {
        Gauge.builder("db.pool.max", () -> maxSize)
                .description("Configured maximum size of the JDBC connection pool")
                .tag("datasource", "default")
                .register(registry);
    }
}
