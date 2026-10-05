package dev.miqui.jobmanager.cache;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import dev.miqui.jobmanager.v1.Job;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Hazelcast map {@code jobs} (key: job id, value: the serialized protobuf Job), on the standalone
 * member in k8s/hazelcast-deployment.yaml, so the cache tier is independent of app pod restarts
 * and scaling.
 *
 * <p>Deliberately no Near Cache: a client-side Near Cache can't be invalidated reliably across pods.
 * Entries carry a TTL only to bound the member's memory - a terminal job never changes, so it can't
 * go stale except by deletion, which evicts it under the per-key lock.
 *
 * <p>Hazelcast has no tracing instrumentation, so every call is a child span of the RPC and a
 * sample of the {@code hazelcast.cache} timer, including lock acquisition and release.
 */
public class HazelcastJobCache implements JobCache {

    public static final String MAP_NAME = "jobs";

    private final HazelcastInstance client;
    private final IMap<String, byte[]> map;
    private final MeterRegistry registry;
    private final Tracer tracer;
    private final Duration ttl;

    public HazelcastJobCache(HazelcastInstance client, MeterRegistry registry, Tracer tracer, Duration ttl) {
        this.client = client;
        this.map = client.getMap(MAP_NAME);
        this.registry = registry;
        this.tracer = tracer;
        this.ttl = ttl;
    }

    @Override
    public <T> T withLock(UUID id, Supplier<T> operation) {
        String key = id.toString();
        boolean locked = observe("lock", "none", () -> {
            try {
                return map.tryLock(key, 5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while acquiring job cache lock", e);
            }
        });
        if (!locked) {
            throw new IllegalStateException("Timed out acquiring job cache lock for " + id);
        }
        try {
            return operation.get();
        } finally {
            observe("unlock", "none", () -> {
                map.unlock(key);
                return null;
            });
        }
    }

    @Override
    public Optional<Job> get(UUID id) {
        Span span = tracer.spanBuilder("hazelcast.get").setSpanKind(SpanKind.CLIENT).startSpan();
        Timer.Sample sample = Timer.start(registry);
        boolean hit = false;
        try (Scope ignored = span.makeCurrent()) {
            byte[] raw = map.get(id.toString());
            hit = raw != null;
            return Optional.ofNullable(raw).map(HazelcastJobCache::parse);
        } finally {
            span.setAttribute("cache.hit", hit);
            sample.stop(timer("get", String.valueOf(hit)));
            span.end();
        }
    }

    @Override
    public void put(Job job) {
        observe("set", "none", () -> {
            map.set(job.getId(), job.toByteArray(), ttl.toMillis(), TimeUnit.MILLISECONDS);
            return null;
        });
    }

    @Override
    public void evict(UUID id) {
        observe("delete", "none", () -> {
            map.delete(id.toString());
            return null;
        });
    }

    @Override
    public boolean connected() {
        return client.getLifecycleService().isRunning();
    }

    private <T> T observe(String operation, String hit, Supplier<T> call) {
        Span span = tracer.spanBuilder("hazelcast." + operation).setSpanKind(SpanKind.CLIENT).startSpan();
        Timer.Sample sample = Timer.start(registry);
        try (Scope ignored = span.makeCurrent()) {
            return call.get();
        } finally {
            sample.stop(timer(operation, hit));
            span.end();
        }
    }

    private Timer timer(String operation, String hit) {
        return Timer.builder("hazelcast.cache")
                .description("Hazelcast job cache calls, including lock acquisition and release")
                .tag("db_system", "hazelcast")
                .tag("db_operation", operation)
                .tag("cache_map", MAP_NAME)
                // Same key set on every operation, so the timer's series stay uniform.
                .tag("cache_hit", hit)
                .serviceLevelObjectives(ms(1), ms(2), ms(5), ms(10), ms(25), ms(50), ms(100))
                .register(registry);
    }

    private static Duration ms(long millis) {
        return Duration.ofMillis(millis);
    }

    private static Job parse(byte[] raw) {
        try {
            return Job.parseFrom(raw);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Unreadable cache entry", e);
        }
    }
}
