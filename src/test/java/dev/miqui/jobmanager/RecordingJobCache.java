package dev.miqui.jobmanager;

import dev.miqui.jobmanager.cache.JobCache;
import dev.miqui.jobmanager.v1.Job;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** In-memory JobCache for the test profile (app.cache.type=memory), recording every call. */
@Singleton
@IfBuildProperty(name = "app.cache.type", stringValue = "memory")
public class RecordingJobCache implements JobCache {

    public final Map<UUID, Job> entries = new ConcurrentHashMap<>();
    public final List<String> calls = new CopyOnWriteArrayList<>();
    public volatile boolean connected = true;
    /** Runs once, just before the next lock is taken (to interleave a concurrent change). */
    public volatile Runnable beforeLock;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public void reset() {
        entries.clear();
        calls.clear();
        connected = true;
        beforeLock = null;
    }

    @Override
    public <T> T withLock(UUID id, Supplier<T> operation) {
        Runnable hook = beforeLock;
        if (hook != null) {
            beforeLock = null;
            hook.run();
        }
        calls.add("lock " + id);
        ReentrantLock lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
            calls.add("unlock " + id);
        }
    }

    @Override
    public Optional<Job> get(UUID id) {
        calls.add("get " + id);
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public void put(Job job) {
        calls.add("put " + job.getId());
        entries.put(UUID.fromString(job.getId()), job);
    }

    @Override
    public void evict(UUID id) {
        calls.add("evict " + id);
        entries.remove(id);
    }

    @Override
    public boolean connected() {
        return connected;
    }
}
