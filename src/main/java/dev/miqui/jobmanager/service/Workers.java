package dev.miqui.jobmanager.service;

import dev.miqui.jobmanager.error.ApiException;
import dev.miqui.jobmanager.repo.Db;
import dev.miqui.jobmanager.repo.JobEventRepository;
import dev.miqui.jobmanager.repo.JobRepository;
import dev.miqui.jobmanager.repo.Json;
import dev.miqui.jobmanager.repo.Rows;
import dev.miqui.jobmanager.v1.Job;
import dev.miqui.jobmanager.v1.JobState;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Worker-side use cases. A worker claims a job (a lease), keeps the lease alive with heartbeats,
 * and finishes it with CompleteJob or FailJob. Every call after the claim is checked against the
 * row locked in the same transaction: the job must still be RUNNING and leased to this worker,
 * otherwise LEASE_NOT_HELD tells the worker to drop the job.
 *
 * <p>No cache interaction: a job is only cached once terminal, and nothing here changes a
 * terminal job.
 */
@ApplicationScoped
public class Workers {

    private final Db db;
    private final JobRepository jobs;
    private final JobEventRepository events;
    private final JobMetrics metrics;

    public Workers(Db db, JobRepository jobs, JobEventRepository events, JobMetrics metrics) {
        this.db = db;
        this.jobs = jobs;
        this.events = events;
        this.metrics = metrics;
    }

    /** leaseSeconds null means the job type's default. Empty when nothing is ready. */
    public Optional<Job> claim(String workerId, List<String> types, Integer leaseSeconds) {
        Optional<Job> claimed = db.tx(c -> {
            Optional<Job> job = jobs.claim(c, workerId, types, leaseSeconds);
            if (job.isPresent()) {
                events.insert(c, UUID.fromString(job.get().getId()), "QUEUED", "RUNNING", actor(workerId),
                        Json.print(Json.object(Map.of("attempt", job.get().getAttempts()))));
            }
            return job;
        });
        metrics.claim(claimed.isPresent());
        claimed.ifPresent(job -> {
            metrics.transition("QUEUED", "RUNNING");
            metrics.queueWait(job.getRunAfter(), job.getUpdatedAt());
        });
        return claimed;
    }

    /** No event and no version bump: a heartbeat is not a state change. */
    public Job heartbeat(UUID id, String workerId, Integer leaseSeconds) {
        return db.tx(c -> {
            held(c, id, workerId);
            return jobs.extendLease(c, id, leaseSeconds);
        });
    }

    public Job complete(UUID id, String workerId, String resultJson) {
        Job job = db.tx(c -> {
            held(c, id, workerId);
            events.insert(c, id, "RUNNING", "SUCCEEDED", actor(workerId), "{}");
            return jobs.succeed(c, id, resultJson);
        });
        metrics.transition("RUNNING", "SUCCEEDED");
        return job;
    }

    /** Re-queued if {@code retryable} and attempts remain, otherwise FAILED. */
    public Job fail(UUID id, String workerId, String message, String detailsJson, boolean retryable) {
        Job job = db.tx(c -> {
            Job current = held(c, id, workerId);
            boolean requeue = retryable && current.getAttempts() < current.getMaxAttempts();
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", message);
            if (detailsJson != null) {
                error.put("details", Json.parse(detailsJson));
            }
            error.put("retryable", retryable);
            String errorJson = Json.print(Json.object(error));
            events.insert(c, id, "RUNNING", requeue ? "QUEUED" : "FAILED", actor(workerId),
                    Json.print(Json.object(Map.of("message", message, "attempt", current.getAttempts()))));
            return jobs.fail(c, id, errorJson, requeue);
        });
        metrics.transition("RUNNING", Rows.column(job.getState()));
        return job;
    }

    /** Locks the job and checks this worker holds its lease. */
    private Job held(Connection c, UUID id, String workerId) throws SQLException {
        Job job = jobs.findForUpdate(c, id).orElseThrow(() -> Jobs.notFound(id));
        if (job.getState() != JobState.JOB_STATE_RUNNING || !job.getLeaseOwner().equals(workerId)) {
            String now = job.getState() == JobState.JOB_STATE_RUNNING
                    ? "leased to another worker" : Rows.column(job.getState());
            throw new ApiException.LeaseNotHeld("Worker '" + workerId + "' does not hold job '" + id
                    + "' (the job is " + now + "); stop working on it.");
        }
        return job;
    }

    static String actor(String workerId) {
        return "worker:" + workerId;
    }
}
