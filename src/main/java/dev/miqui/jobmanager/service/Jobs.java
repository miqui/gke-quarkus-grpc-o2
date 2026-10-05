package dev.miqui.jobmanager.service;

import com.google.rpc.BadRequest.FieldViolation;
import dev.miqui.jobmanager.cache.JobCache;
import dev.miqui.jobmanager.error.ApiException;
import dev.miqui.jobmanager.repo.Db;
import dev.miqui.jobmanager.repo.JobEventRepository;
import dev.miqui.jobmanager.repo.JobRepository;
import dev.miqui.jobmanager.repo.Json;
import dev.miqui.jobmanager.repo.Rows;
import dev.miqui.jobmanager.v1.Job;
import dev.miqui.jobmanager.v1.ListJobEventsResponse;
import dev.miqui.jobmanager.v1.ListJobsResponse;
import io.grpc.Status;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Client-side use cases: submit, observe, cancel and delete jobs. */
@ApplicationScoped
public class Jobs {

    static final String API_ACTOR = "api";

    private final Db db;
    private final JobRepository jobs;
    private final JobEventRepository events;
    private final JobCache cache;
    private final JobMetrics metrics;

    public Jobs(Db db, JobRepository jobs, JobEventRepository events, JobCache cache, JobMetrics metrics) {
        this.db = db;
        this.jobs = jobs;
        this.events = events;
        this.cache = cache;
        this.metrics = metrics;
    }

    /**
     * With an idempotency key that was already used, returns the job the first call created (and
     * records nothing). The type was validated against the registry by the caller.
     */
    public Job create(JobRepository.NewJob request) {
        Created created = db.tx(c -> {
            Optional<Job> inserted = jobs.insert(c, request);
            if (inserted.isEmpty()) {
                // Either the key was taken, or the type was unregistered since validation.
                Optional<Job> existing = request.idempotencyKey() == null
                        ? Optional.empty() : jobs.findByIdempotencyKey(c, request.idempotencyKey());
                return new Created(existing.orElseThrow(() -> unknownType(request.type())), false);
            }
            Job job = inserted.get();
            events.insert(c, UUID.fromString(job.getId()), null, "QUEUED", API_ACTOR, "{}");
            return new Created(job, true);
        });
        if (created.isNew()) {
            metrics.transition(null, "QUEUED");
        }
        return created.job();
    }

    private record Created(Job job, boolean isNew) {
    }

    /** A state change: the state the job left, and the job as it is now. */
    record Transition(String from, Job job) {
    }

    /**
     * Cache-aside for terminal jobs. A hit needs no lock: a terminal job never changes, and a
     * delete evicts it under the lock. A miss reads the database; only a terminal job is then
     * cached, re-read under the lock so a concurrent delete can't be undone by the fill.
     */
    public Job get(UUID id) {
        Optional<Job> cached = cache.get(id);
        if (cached.isPresent()) {
            return cached.get();
        }
        Job job = db.tx(c -> jobs.find(c, id)).orElseThrow(() -> notFound(id));
        if (!Rows.terminal(job.getState())) {
            return job;
        }
        return cache.withLock(id, () -> {
            Job current = db.tx(c -> jobs.find(c, id)).orElseThrow(() -> notFound(id));
            cache.put(current);
            return current;
        });
    }

    public ListJobsResponse list(JobRepository.Filter filter, int limit, int offset) {
        return db.tx(c -> ListJobsResponse.newBuilder()
                .addAllItems(jobs.page(c, filter, limit, offset))
                .setTotalCount(jobs.count(c, filter))
                .build());
    }

    public ListJobEventsResponse events(UUID id, int limit, int offset) {
        return db.tx(c -> {
            if (jobs.find(c, id).isEmpty()) {
                throw notFound(id);
            }
            return ListJobEventsResponse.newBuilder()
                    .addAllItems(events.page(c, id, limit, offset))
                    .setTotalCount(events.count(c, id))
                    .build();
        });
    }

    /**
     * QUEUED or RUNNING -> CANCELLED; a worker holding it learns on its next call (LEASE_NOT_HELD).
     * Never cached before (not terminal), so there is nothing to evict.
     */
    public Job cancel(UUID id, Integer version, String reason) {
        Transition cancelled = db.tx(c -> {
            Job job = jobs.findForUpdate(c, id).orElseThrow(() -> notFound(id));
            if (Rows.terminal(job.getState())) {
                throw new ApiException.Conflict(Status.Code.FAILED_PRECONDITION, "Job with ID '" + id
                        + "' is already " + Rows.column(job.getState()) + " and cannot be cancelled.");
            }
            if (version != null && job.getVersion() != version) {
                throw new ApiException.Conflict(Status.Code.ABORTED, "Job with ID '" + id
                        + "' has changed since version " + version + " was read; refetch and retry.");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            if (reason != null) {
                detail.put("reason", reason);
            }
            if (!job.getLeaseOwner().isEmpty()) {
                detail.put("worker_id", job.getLeaseOwner());
            }
            events.insert(c, id, Rows.column(job.getState()), "CANCELLED", API_ACTOR, Json.print(Json.object(detail)));
            return new Transition(Rows.column(job.getState()), jobs.cancel(c, id));
        });
        metrics.transition(cancelled.from(), "CANCELLED");
        return cancelled.job();
    }

    /** Terminal jobs only, with their events. Evicted under the lock, after the commit. */
    public void delete(UUID id) {
        cache.withLock(id, () -> {
            db.tx(c -> {
                Job job = jobs.findForUpdate(c, id).orElseThrow(() -> notFound(id));
                if (!Rows.terminal(job.getState())) {
                    throw new ApiException.Conflict(Status.Code.FAILED_PRECONDITION, "Job with ID '" + id
                            + "' is " + Rows.column(job.getState()) + "; only finished jobs can be deleted - cancel it first.");
                }
                jobs.delete(c, id);
                return null;
            });
            cache.evict(id);
            return null;
        });
    }

    static ApiException notFound(UUID id) {
        return new ApiException.NotFound("Job with ID '" + id + "' was not found.");
    }

    static ApiException unknownType(String type) {
        return new ApiException.BadInput(List.of(FieldViolation.newBuilder()
                .setField("type").setDescription("type '" + type + "' is not a registered job type").build()));
    }
}
