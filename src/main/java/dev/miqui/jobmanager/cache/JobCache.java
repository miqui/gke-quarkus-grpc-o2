package dev.miqui.jobmanager.cache;

import dev.miqui.jobmanager.v1.Job;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cache-aside store for GetJob (key: job id), holding terminal jobs only: they never change again,
 * so an entry can only go stale by its job being deleted. Fills and deletes are serialized per id
 * across all replicas ({@link #withLock}) so a fill can never re-insert a job a delete just removed.
 */
public interface JobCache {

    /** Serializes cache fills and deletes for an id across all application replicas. */
    <T> T withLock(UUID id, Supplier<T> operation);

    Optional<Job> get(UUID id);

    void put(Job job);

    void evict(UUID id);

    /** False once the client has given up for good; a restart is the only recovery. */
    boolean connected();
}
