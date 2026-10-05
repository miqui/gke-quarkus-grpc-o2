package dev.miqui.jobmanager.config;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.impl.connection.tcp.RoutingMode;
import com.hazelcast.core.HazelcastInstance;
import dev.miqui.jobmanager.cache.HazelcastJobCache;
import dev.miqui.jobmanager.cache.JobCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.Startup;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Duration;

/**
 * The Hazelcast client. The cache is mandatory: if the member is unreachable, startup fails
 * rather than running without it. The connect timeout is bounded (the client default retries
 * forever) so a dead member fails well inside the startup-probe budget.
 *
 * <p>Tests build with {@code app.cache.type=memory} and provide their own {@link JobCache}.
 */
@ApplicationScoped
@IfBuildProperty(name = "app.cache.type", stringValue = "hazelcast", enableIfMissing = true)
public class CacheConfig {

    public static final String CLUSTER_NAME = "message-service-cache";

    @ConfigMapping(prefix = "app.hazelcast")
    public interface HazelcastProperties {
        @WithDefault("localhost")
        String host();

        @WithDefault("5701")
        int port();

        @WithDefault("20s")
        Duration connectTimeout();

        @WithDefault("1h")
        Duration ttl();
    }

    @Produces
    @Singleton
    @Startup
    HazelcastInstance hazelcastClient(HazelcastProperties props) {
        ClientConfig config = new ClientConfig();
        // The member's cluster name (k8s/hazelcast-deployment.yaml) is unchanged.
        config.setClusterName(CLUSTER_NAME);
        config.getNetworkConfig().addAddress(props.host() + ":" + props.port());
        // One member behind a ClusterIP Service: talk to the address given, not to every member.
        config.getNetworkConfig().getClusterRoutingConfig().setRoutingMode(RoutingMode.SINGLE_MEMBER);
        config.getConnectionStrategyConfig().getConnectionRetryConfig()
                .setClusterConnectTimeoutMillis(props.connectTimeout().toMillis());
        config.setProperty("hazelcast.logging.type", "jdk");
        return HazelcastClient.newHazelcastClient(config);
    }

    void shutdown(@Disposes HazelcastInstance client) {
        client.shutdown();
    }

    @Produces
    @Singleton
    JobCache jobCache(HazelcastInstance client, MeterRegistry registry, Tracer tracer, HazelcastProperties props) {
        return new HazelcastJobCache(client, registry, tracer, props.ttl());
    }
}
