package dev.miqui.jobmanager.config;

import dev.miqui.jobmanager.cache.JobCache;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness check {@code hazelcastClient}. A client that gave up reconnecting (the member was gone
 * longer than the connect timeout, e.g. rescheduled by a node scale-down) shuts itself down for
 * good, and every cache call then fails - restarting the container is the only recovery. A local
 * lifecycle flag, not a network call, so a slow member can't fail the probe.
 */
@Liveness
@ApplicationScoped
public class CacheClientHealthCheck implements HealthCheck {

    private final JobCache cache;

    public CacheClientHealthCheck(JobCache cache) {
        this.cache = cache;
    }

    @Override
    public HealthCheckResponse call() {
        var response = HealthCheckResponse.named("hazelcastClient");
        return cache.connected()
                ? response.up().build()
                : response.down().withData("reason", "cache client shut down").build();
    }
}
