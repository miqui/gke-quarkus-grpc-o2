package dev.miqui.jobmanager.cache;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Two real clients against an embedded member: the per-id lock is distributed. */
class HazelcastJobCacheLockTest {

    private static HazelcastInstance member;
    private static HazelcastInstance firstClient;
    private static HazelcastInstance secondClient;

    @BeforeAll
    static void startHazelcast() {
        Config config = new Config().setClusterName("cache-lock-test-" + UUID.randomUUID());
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.logging.type", "none");
        config.setProperty("hazelcast.operation.thread.count", "2");
        config.setProperty("hazelcast.operation.generic.thread.count", "2");
        config.setProperty("hazelcast.io.thread.count", "2");
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().setPort(0).getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        var join = config.getNetworkConfig().getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        member = Hazelcast.newHazelcastInstance(config);

        ClientConfig clientConfig = new ClientConfig().setClusterName(config.getClusterName());
        clientConfig.setProperty("hazelcast.logging.type", "none");
        clientConfig.getNetworkConfig().addAddress(
                "127.0.0.1:" + member.getCluster().getLocalMember().getAddress().getPort());
        clientConfig.getConnectionStrategyConfig().getConnectionRetryConfig().setClusterConnectTimeoutMillis(5000);
        firstClient = HazelcastClient.newHazelcastClient(clientConfig);
        secondClient = HazelcastClient.newHazelcastClient(clientConfig);
    }

    @AfterAll
    static void stopHazelcast() {
        if (secondClient != null) {
            secondClient.shutdown();
        }
        if (firstClient != null) {
            firstClient.shutdown();
        }
        if (member != null) {
            member.shutdown();
        }
    }

    private static HazelcastJobCache cache(HazelcastInstance client) {
        return new HazelcastJobCache(client, new SimpleMeterRegistry(), OpenTelemetry.noop().getTracer("test"),
                Duration.ofMinutes(1));
    }

    @Test
    void independentClientsSerializeTheSameIdButNotOtherIds() throws Exception {
        var first = cache(firstClient);
        var second = cache(secondClient);
        UUID id = UUID.randomUUID();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondAttempted = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> first.withLock(id, () -> {
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release test lock");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted test lock", e);
                }
                return "first";
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var waiter = pool.submit(() -> {
                    secondAttempted.countDown();
                    return second.withLock(id, () -> "second");
                });
                assertThat(secondAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThrows(TimeoutException.class, () -> waiter.get(200, TimeUnit.MILLISECONDS));
                assertThat(second.withLock(UUID.randomUUID(), () -> "other")).isEqualTo("other");

                release.countDown();
                assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("first");
                assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo("second");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void anEntryWrittenByOneClientIsReadByTheOther() {
        var job = dev.miqui.jobmanager.v1.Job.newBuilder().setId(UUID.randomUUID().toString()).build();
        cache(firstClient).put(job);
        assertThat(cache(secondClient).get(UUID.fromString(job.getId()))).contains(job);
        cache(secondClient).evict(UUID.fromString(job.getId()));
        assertThat(cache(firstClient).get(UUID.fromString(job.getId()))).isEmpty();
    }
}
