package dev.miqui.jobmanager.error;

import com.google.rpc.BadRequest;
import io.grpc.Status;

import java.util.List;

/**
 * An expected failure with a stable, client-facing {@link #reason()} (google.rpc.ErrorInfo.reason)
 * and a gRPC status. Mapped to a google.rpc.Status by {@code GrpcErrorHandler}; anything that is
 * not an ApiException becomes a generic INTERNAL.
 */
public abstract sealed class ApiException extends RuntimeException {

    private final Status.Code code;
    private final String reason;

    ApiException(Status.Code code, String reason, String message) {
        super(message, null, false, false);
        this.code = code;
        this.reason = reason;
    }

    public Status.Code code() {
        return code;
    }

    public String reason() {
        return reason;
    }

    /** INVALID_ARGUMENT with every failing field at once. */
    public static final class BadInput extends ApiException {
        private final List<BadRequest.FieldViolation> violations;

        public BadInput(List<BadRequest.FieldViolation> violations) {
            super(Status.Code.INVALID_ARGUMENT, "BAD_USER_INPUT",
                    "The request content was invalid or failed validation constraints.");
            this.violations = List.copyOf(violations);
        }

        public List<BadRequest.FieldViolation> violations() {
            return violations;
        }
    }

    public static final class NotFound extends ApiException {
        public NotFound(String message) {
            super(Status.Code.NOT_FOUND, "NOT_FOUND", message);
        }
    }

    /**
     * Every conflict shares the CONFLICT reason but carries the gRPC code that fits it: ABORTED
     * (stale version - retry after a refetch), FAILED_PRECONDITION (the job's state doesn't allow
     * the operation, e.g. cancelling a finished job).
     */
    public static final class Conflict extends ApiException {
        public Conflict(Status.Code code, String message) {
            super(code, "CONFLICT", message);
        }
    }

    /**
     * FAILED_PRECONDITION for a worker call on a job it no longer holds: cancelled, re-queued after
     * its lease expired, or claimed by someone else. The worker should stop working on it.
     */
    public static final class LeaseNotHeld extends ApiException {
        public LeaseNotHeld(String message) {
            super(Status.Code.FAILED_PRECONDITION, "LEASE_NOT_HELD", message);
        }
    }
}
