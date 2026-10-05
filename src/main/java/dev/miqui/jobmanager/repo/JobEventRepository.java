package dev.miqui.jobmanager.repo;

import dev.miqui.jobmanager.v1.JobEvent;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** The job_events audit trail: one row per state change, written in the same transaction. */
@ApplicationScoped
public class JobEventRepository {

    /** fromState null for the creation event. */
    public void insert(Connection c, UUID jobId, String fromState, String toState, String actor, String detailJson)
            throws SQLException {
        Db.update(c, "INSERT INTO job_events (job_id, from_state, to_state, actor, detail) VALUES (?, ?, ?, ?, ?::jsonb)",
                jobId, fromState, toState, actor, detailJson);
    }

    /** Oldest first. */
    public List<JobEvent> page(Connection c, UUID jobId, int limit, int offset) throws SQLException {
        return Db.list(c, """
                        SELECT id, job_id, from_state, to_state, actor, detail::text AS detail, at
                        FROM job_events WHERE job_id = ? ORDER BY id LIMIT ? OFFSET ?
                        """,
                Rows::event, jobId, limit, offset);
    }

    public long count(Connection c, UUID jobId) throws SQLException {
        return Db.count(c, "SELECT count(*) FROM job_events WHERE job_id = ?", jobId);
    }
}
