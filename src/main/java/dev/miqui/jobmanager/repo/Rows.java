package dev.miqui.jobmanager.repo;

import com.google.protobuf.Timestamp;
import dev.miqui.jobmanager.v1.Job;
import dev.miqui.jobmanager.v1.JobError;
import dev.miqui.jobmanager.v1.JobEvent;
import dev.miqui.jobmanager.v1.JobState;
import dev.miqui.jobmanager.v1.JobType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

/** Row -> protobuf mapping shared by the repositories. */
public final class Rows {

    /** Every column {@link #job} reads, in a SELECT list or RETURNING clause. */
    public static final String JOB_COLUMNS = """
            id, name, type, state, priority, spec::text AS spec, labels::text AS labels,
            result::text AS result, error::text AS error, attempts, max_attempts, run_after,
            lease_owner, lease_expires_at, version, idempotency_key, created_at, updated_at,
            started_at, finished_at""";

    private Rows() {
    }

    /** 'QUEUED' <-> JOB_STATE_QUEUED. */
    public static JobState state(String column) {
        return column == null ? JobState.JOB_STATE_UNSPECIFIED : JobState.valueOf("JOB_STATE_" + column);
    }

    public static String column(JobState state) {
        return state.name().substring("JOB_STATE_".length());
    }

    public static boolean terminal(JobState state) {
        return state == JobState.JOB_STATE_SUCCEEDED || state == JobState.JOB_STATE_FAILED
                || state == JobState.JOB_STATE_CANCELLED;
    }

    public static Timestamp timestamp(OffsetDateTime t) {
        return Timestamp.newBuilder().setSeconds(t.toEpochSecond()).setNanos(t.getNano()).build();
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    public static Job job(ResultSet rs) throws SQLException {
        Job.Builder job = Job.newBuilder()
                .setId(rs.getString("id"))
                .setName(rs.getString("name"))
                .setType(rs.getString("type"))
                .setState(state(rs.getString("state")))
                .setPriority(rs.getInt("priority"))
                .setSpec(Json.parse(rs.getString("spec")))
                .putAllLabels(Json.labels(rs.getString("labels")))
                .setAttempts(rs.getInt("attempts"))
                .setMaxAttempts(rs.getInt("max_attempts"))
                .setRunAfter(timestamp(time(rs, "run_after")))
                .setVersion(rs.getInt("version"))
                .setCreatedAt(timestamp(time(rs, "created_at")))
                .setUpdatedAt(timestamp(time(rs, "updated_at")));
        String result = rs.getString("result");
        if (result != null) {
            job.setResult(Json.parse(result));
        }
        String error = rs.getString("error");
        if (error != null) {
            job.setError(error(error));
        }
        String leaseOwner = rs.getString("lease_owner");
        if (leaseOwner != null) {
            job.setLeaseOwner(leaseOwner).setLeaseExpiresAt(timestamp(time(rs, "lease_expires_at")));
        }
        String idempotencyKey = rs.getString("idempotency_key");
        if (idempotencyKey != null) {
            job.setIdempotencyKey(idempotencyKey);
        }
        OffsetDateTime startedAt = time(rs, "started_at");
        if (startedAt != null) {
            job.setStartedAt(timestamp(startedAt));
        }
        OffsetDateTime finishedAt = time(rs, "finished_at");
        if (finishedAt != null) {
            job.setFinishedAt(timestamp(finishedAt));
        }
        return job.build();
    }

    /** The error column: {"message": ..., "details": {...}, "retryable": bool}. */
    private static JobError error(String json) {
        var fields = Json.parse(json).getFieldsMap();
        JobError.Builder error = JobError.newBuilder()
                .setMessage(fields.containsKey("message") ? fields.get("message").getStringValue() : "")
                .setRetryable(fields.containsKey("retryable") && fields.get("retryable").getBoolValue());
        if (fields.containsKey("details") && fields.get("details").hasStructValue()) {
            error.setDetails(fields.get("details").getStructValue());
        }
        return error.build();
    }

    public static JobEvent event(ResultSet rs) throws SQLException {
        return JobEvent.newBuilder()
                .setId(rs.getLong("id"))
                .setJobId(rs.getString("job_id"))
                .setFromState(state(rs.getString("from_state")))
                .setToState(state(rs.getString("to_state")))
                .setActor(rs.getString("actor"))
                .setDetail(Json.parse(rs.getString("detail")))
                .setAt(timestamp(time(rs, "at")))
                .build();
    }

    public static JobType type(ResultSet rs) throws SQLException {
        return JobType.newBuilder()
                .setName(rs.getString("name"))
                .setDescription(rs.getString("description"))
                .setDefaultMaxAttempts(rs.getInt("default_max_attempts"))
                .setDefaultLeaseSeconds(rs.getInt("default_lease_seconds"))
                .build();
    }
}
