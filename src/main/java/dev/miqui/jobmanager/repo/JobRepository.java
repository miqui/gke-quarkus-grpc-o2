package dev.miqui.jobmanager.repo;

import dev.miqui.jobmanager.v1.Job;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dev.miqui.jobmanager.repo.Rows.JOB_COLUMNS;

/**
 * The jobs table. State changes other than claiming and lease expiry are applied to a row the
 * caller has already locked ({@link #findForUpdate}) and checked, so the reason a change is refused
 * (wrong state, stale version, lease held by someone else) is decided once, without a race.
 */
@ApplicationScoped
public class JobRepository {

    private static final String SELECT = "SELECT " + JOB_COLUMNS + " FROM jobs";

    public record NewJob(String name, String type, int priority, String specJson, String labelsJson,
                         Integer maxAttempts, Instant runAfter, String idempotencyKey) {
    }

    /** ListJobs filters; null/empty means no filter. */
    public record Filter(List<String> states, String type, String labelsJson) {
        public Filter {
            states = List.copyOf(states);
        }
    }

    /**
     * Empty when the idempotency key was already used (the caller then reads that job). A
     * concurrent insert with the same key waits for the first to commit, then also returns empty.
     * max_attempts null means the type's default.
     */
    public Optional<Job> insert(Connection c, NewJob job) throws SQLException {
        return Db.one(c, """
                        INSERT INTO jobs (name, type, priority, spec, labels, max_attempts, run_after, idempotency_key)
                        SELECT ?, t.name, ?, ?::jsonb, ?::jsonb, COALESCE(?::int, t.default_max_attempts),
                               COALESCE(?::timestamptz, now()), ?
                        FROM job_types t WHERE t.name = ?
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING\s""" + JOB_COLUMNS,
                Rows::job,
                job.name(), job.priority(), job.specJson(), job.labelsJson(), job.maxAttempts(),
                job.runAfter(), job.idempotencyKey(), job.type());
    }

    public Optional<Job> find(Connection c, UUID id) throws SQLException {
        return Db.one(c, SELECT + " WHERE id = ?", Rows::job, id);
    }

    /** Locks the row until the transaction ends. */
    public Optional<Job> findForUpdate(Connection c, UUID id) throws SQLException {
        return Db.one(c, SELECT + " WHERE id = ? FOR UPDATE", Rows::job, id);
    }

    public Optional<Job> findByIdempotencyKey(Connection c, String key) throws SQLException {
        return Db.one(c, SELECT + " WHERE idempotency_key = ?", Rows::job, key);
    }

    /** Newest first. */
    public List<Job> page(Connection c, Filter filter, int limit, int offset) throws SQLException {
        List<Object> params = new ArrayList<>();
        String where = where(filter, params);
        params.add(limit);
        params.add(offset);
        return Db.list(c, SELECT + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                Rows::job, params.toArray());
    }

    public long count(Connection c, Filter filter) throws SQLException {
        List<Object> params = new ArrayList<>();
        return Db.count(c, "SELECT count(*) FROM jobs" + where(filter, params), params.toArray());
    }

    private static String where(Filter filter, List<Object> params) {
        List<String> conditions = new ArrayList<>();
        if (!filter.states().isEmpty()) {
            conditions.add("state = ANY(?)");
            params.add(new Db.TextArray(filter.states()));
        }
        if (filter.type() != null) {
            conditions.add("type = ?");
            params.add(filter.type());
        }
        if (filter.labelsJson() != null) {
            conditions.add("labels @> ?::jsonb");
            params.add(filter.labelsJson());
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    /**
     * Leases the next ready QUEUED job of one of {@code types}: highest priority, then oldest
     * run_after. SKIP LOCKED makes concurrent claims take different rows instead of queueing behind
     * one another. leaseSeconds null means the type's default.
     */
    public Optional<Job> claim(Connection c, String workerId, List<String> types, Integer leaseSeconds)
            throws SQLException {
        return Db.one(c, """
                        WITH next AS (
                            SELECT id FROM jobs
                            WHERE state = 'QUEUED' AND run_after <= now() AND type = ANY(?)
                            ORDER BY priority DESC, run_after, id
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED
                        ), claimed AS (
                            UPDATE jobs j
                            SET state = 'RUNNING', attempts = j.attempts + 1, lease_owner = ?,
                                lease_expires_at = now() + make_interval(secs => COALESCE(?::int, t.default_lease_seconds)),
                                started_at = COALESCE(j.started_at, now()), updated_at = now(), version = j.version + 1
                            FROM next, job_types t
                            WHERE j.id = next.id AND t.name = j.type
                            RETURNING j.*
                        )
                        SELECT\s""" + JOB_COLUMNS + " FROM claimed",
                Rows::job, new Db.TextArray(types), workerId, leaseSeconds);
    }

    /** Lease counted from now; leaseSeconds null means the type's default. No version bump. */
    public Job extendLease(Connection c, UUID id, Integer leaseSeconds) throws SQLException {
        return Db.one(c, """
                        UPDATE jobs
                        SET lease_expires_at = now() + make_interval(secs => COALESCE(?::int,
                                (SELECT default_lease_seconds FROM job_types t WHERE t.name = jobs.type))),
                            updated_at = now()
                        WHERE id = ?
                        RETURNING\s""" + JOB_COLUMNS,
                Rows::job, leaseSeconds, id).orElseThrow();
    }

    public Job succeed(Connection c, UUID id, String resultJson) throws SQLException {
        return Db.one(c, """
                        UPDATE jobs
                        SET state = 'SUCCEEDED', result = ?::jsonb, lease_owner = NULL, lease_expires_at = NULL,
                            finished_at = now(), updated_at = now(), version = version + 1
                        WHERE id = ?
                        RETURNING\s""" + JOB_COLUMNS,
                Rows::job, resultJson, id).orElseThrow();
    }

    /**
     * Back to QUEUED when {@code requeue}, claimable again after an exponential backoff (5s, 10s,
     * 20s, ... capped at 5 minutes); otherwise FAILED.
     */
    public Job fail(Connection c, UUID id, String errorJson, boolean requeue) throws SQLException {
        return Db.one(c, """
                        UPDATE jobs
                        SET state = CASE WHEN ? THEN 'QUEUED' ELSE 'FAILED' END,
                            error = ?::jsonb, lease_owner = NULL, lease_expires_at = NULL,
                            run_after = CASE WHEN ? THEN now() + make_interval(secs => LEAST(300, 5 * power(2, attempts - 1)))
                                             ELSE run_after END,
                            finished_at = CASE WHEN ? THEN NULL ELSE now() END,
                            updated_at = now(), version = version + 1
                        WHERE id = ?
                        RETURNING\s""" + JOB_COLUMNS,
                Rows::job, requeue, errorJson, requeue, requeue, id).orElseThrow();
    }

    public Job cancel(Connection c, UUID id) throws SQLException {
        return Db.one(c, """
                        UPDATE jobs
                        SET state = 'CANCELLED', lease_owner = NULL, lease_expires_at = NULL,
                            finished_at = now(), updated_at = now(), version = version + 1
                        WHERE id = ?
                        RETURNING\s""" + JOB_COLUMNS,
                Rows::job, id).orElseThrow();
    }

    public void delete(Connection c, UUID id) throws SQLException {
        Db.update(c, "DELETE FROM jobs WHERE id = ?", id);
    }

    /** A job that expired its lease: where it went, and which worker lost it. */
    public record Expired(String jobId, String toState, String worker) {
    }

    /**
     * Re-queues (attempts left) or fails (none left) up to {@code batch} RUNNING jobs whose lease
     * has passed, recording an event for each, in one statement. SKIP LOCKED lets every replica run
     * the reaper without blocking on, or double-processing, the same rows.
     */
    public List<Expired> expireLeases(Connection c, int batch) throws SQLException {
        return Db.list(c, """
                        WITH expired AS (
                            SELECT id, lease_owner FROM jobs
                            WHERE state = 'RUNNING' AND lease_expires_at < now()
                            ORDER BY lease_expires_at
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                        ), moved AS (
                            UPDATE jobs j
                            SET state = CASE WHEN j.attempts < j.max_attempts THEN 'QUEUED' ELSE 'FAILED' END,
                                error = jsonb_build_object(
                                    'message', 'lease expired',
                                    'details', jsonb_build_object('worker_id', expired.lease_owner),
                                    'retryable', j.attempts < j.max_attempts),
                                lease_owner = NULL, lease_expires_at = NULL,
                                finished_at = CASE WHEN j.attempts < j.max_attempts THEN NULL ELSE now() END,
                                updated_at = now(), version = j.version + 1
                            FROM expired
                            WHERE j.id = expired.id
                            RETURNING j.id, j.state, expired.lease_owner AS worker
                        )
                        INSERT INTO job_events (job_id, from_state, to_state, actor, detail)
                        SELECT id, 'RUNNING', state, 'system:lease-reaper',
                               jsonb_build_object('reason', 'lease expired', 'worker_id', worker)
                        FROM moved
                        RETURNING job_id, to_state, detail->>'worker_id' AS worker
                        """,
                rs -> new Expired(rs.getString("job_id"), rs.getString("to_state"), rs.getString("worker")),
                batch);
    }

    /** Job count per state column value. */
    public List<StateCount> countByState(Connection c) throws SQLException {
        return Db.list(c, "SELECT state, count(*) AS n FROM jobs GROUP BY state",
                rs -> new StateCount(rs.getString("state"), rs.getLong("n")));
    }

    public record StateCount(String state, long count) {
    }
}
