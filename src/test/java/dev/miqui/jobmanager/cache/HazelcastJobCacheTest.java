package dev.miqui.jobmanager.cache;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.map.IMap;
import dev.miqui.jobmanager.v1.Job;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HazelcastJobCacheTest {

    private final UUID id = UUID.randomUUID();
    @SuppressWarnings("unchecked")
    private final IMap<String, byte[]> map = mock(IMap.class);
    private final HazelcastInstance client = mock(HazelcastInstance.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private HazelcastJobCache cache;

    @BeforeEach
    void setUp() {
        when(client.<String, byte[]>getMap(HazelcastJobCache.MAP_NAME)).thenReturn(map);
        cache = new HazelcastJobCache(client, registry, OpenTelemetry.noop().getTracer("test"), Duration.ofHours(1));
    }

    @Test
    void locksBeforeTheOperationAndUnlocksAfterwards() throws Exception {
        when(map.tryLock(id.toString(), 5, TimeUnit.SECONDS)).thenReturn(true);
        @SuppressWarnings("unchecked")
        Supplier<String> operation = mock(Supplier.class);
        when(operation.get()).thenReturn("result");

        assertThat(cache.withLock(id, operation)).isEqualTo("result");

        var order = inOrder(map, operation);
        order.verify(map).tryLock(id.toString(), 5, TimeUnit.SECONDS);
        order.verify(operation).get();
        order.verify(map).unlock(id.toString());
        assertThat(registry.get("hazelcast.cache").tag("db_operation", "lock").timer().count()).isEqualTo(1);
    }

    @Test
    void releasesTheLockWhenTheOperationFails() throws Exception {
        when(map.tryLock(id.toString(), 5, TimeUnit.SECONDS)).thenReturn(true);
        var failure = new IllegalStateException("operation failed");

        assertThat(assertThrows(IllegalStateException.class, () -> cache.withLock(id, () -> {
            throw failure;
        }))).isSameAs(failure);
        verify(map).unlock(id.toString());
    }

    @Test
    void timeoutDoesNotRunTheOperationOrUnlockAnUnownedLock() {
        @SuppressWarnings("unchecked")
        Supplier<String> operation = mock(Supplier.class);

        assertThrows(IllegalStateException.class, () -> cache.withLock(id, operation));
        verify(operation, never()).get();
        verify(map, never()).unlock(id.toString());
    }

    @Test
    void interruptionIsPreservedAndDoesNotRunTheOperation() throws Exception {
        when(map.tryLock(id.toString(), 5, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        @SuppressWarnings("unchecked")
        Supplier<String> operation = mock(Supplier.class);

        try {
            assertThrows(IllegalStateException.class, () -> cache.withLock(id, operation));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(operation, never()).get();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void entriesAreProtobufBytesWithATtl() {
        Job job = Job.newBuilder().setId(id.toString()).setName("n").build();
        cache.put(job);
        verify(map).set(id.toString(), job.toByteArray(), Duration.ofHours(1).toMillis(), TimeUnit.MILLISECONDS);

        when(map.get(id.toString())).thenReturn(job.toByteArray());
        assertThat(cache.get(id)).contains(job);
        assertThat(registry.get("hazelcast.cache").tag("db_operation", "get").tag("cache_hit", "true").timer().count())
                .isEqualTo(1);

        when(map.get(id.toString())).thenReturn(null);
        assertThat(cache.get(id)).isEmpty();

        cache.evict(id);
        verify(map).delete(id.toString());
    }

    @Test
    void anUnreadableEntryFailsLoudly() {
        when(map.get(id.toString())).thenReturn(new byte[]{(byte) 0xff, 0x01});
        assertThrows(IllegalStateException.class, () -> cache.get(id));
    }

    @Test
    void connectedFollowsTheClientLifecycle() {
        LifecycleService lifecycle = mock(LifecycleService.class);
        when(client.getLifecycleService()).thenReturn(lifecycle);
        when(lifecycle.isRunning()).thenReturn(true, false);
        assertThat(cache.connected()).isTrue();
        assertThat(cache.connected()).isFalse();
    }
}
