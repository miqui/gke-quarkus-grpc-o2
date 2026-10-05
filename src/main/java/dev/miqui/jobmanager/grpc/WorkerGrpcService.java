package dev.miqui.jobmanager.grpc;

import dev.miqui.jobmanager.service.JobTypes;
import dev.miqui.jobmanager.service.Workers;
import dev.miqui.jobmanager.v1.ClaimJobRequest;
import dev.miqui.jobmanager.v1.ClaimJobResponse;
import dev.miqui.jobmanager.v1.CompleteJobRequest;
import dev.miqui.jobmanager.v1.FailJobRequest;
import dev.miqui.jobmanager.v1.HeartbeatRequest;
import dev.miqui.jobmanager.v1.Job;
import dev.miqui.jobmanager.v1.WorkerServiceGrpc;
import dev.miqui.jobmanager.validation.Violations;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.RunOnVirtualThread;

import java.util.List;
import java.util.UUID;

import static dev.miqui.jobmanager.validation.Violations.CLAIM_TYPES_MAX;
import static dev.miqui.jobmanager.validation.Violations.LEASE_SECONDS_MAX;
import static dev.miqui.jobmanager.validation.Violations.LEASE_SECONDS_MIN;
import static dev.miqui.jobmanager.validation.Violations.MESSAGE_MAX;

/** Validation and proto plumbing only; exceptions are mapped by {@link Rpc#unary}. */
@GrpcService
@RunOnVirtualThread
public class WorkerGrpcService extends WorkerServiceGrpc.WorkerServiceImplBase {

    private final Workers workers;
    private final JobTypes types;

    public WorkerGrpcService(Workers workers, JobTypes types) {
        this.workers = workers;
        this.types = types;
    }

    @Override
    public void claimJob(ClaimJobRequest request, StreamObserver<ClaimJobResponse> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            String workerId = v.workerId("worker_id", request.getWorkerId());
            List<String> claimTypes = request.getTypesList();
            if (claimTypes.isEmpty() || claimTypes.size() > CLAIM_TYPES_MAX) {
                v.add("types", "types must name between 1 and " + CLAIM_TYPES_MAX + " job types");
            } else {
                var registered = types.all();
                for (int i = 0; i < claimTypes.size(); i++) {
                    if (!registered.containsKey(claimTypes.get(i))) {
                        v.add("types[" + i + "]", "type '" + claimTypes.get(i) + "' is not a registered job type");
                    }
                }
            }
            Integer lease = leaseSeconds(v, request.hasLeaseSeconds(), request.getLeaseSeconds());
            v.throwIfAny();
            ClaimJobResponse.Builder claimed = ClaimJobResponse.newBuilder();
            workers.claim(workerId, claimTypes, lease).ifPresent(claimed::setJob);
            return claimed.build();
        });
    }

    @Override
    public void heartbeat(HeartbeatRequest request, StreamObserver<Job> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("job_id", request.getJobId());
            String workerId = v.workerId("worker_id", request.getWorkerId());
            Integer lease = leaseSeconds(v, request.hasLeaseSeconds(), request.getLeaseSeconds());
            v.throwIfAny();
            return workers.heartbeat(id, workerId, lease);
        });
    }

    @Override
    public void completeJob(CompleteJobRequest request, StreamObserver<Job> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("job_id", request.getJobId());
            String workerId = v.workerId("worker_id", request.getWorkerId());
            String result = request.hasResult() ? v.json("result", request.getResult()) : "{}";
            v.throwIfAny();
            return workers.complete(id, workerId, result);
        });
    }

    @Override
    public void failJob(FailJobRequest request, StreamObserver<Job> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("job_id", request.getJobId());
            String workerId = v.workerId("worker_id", request.getWorkerId());
            String message = v.text("message", request.getMessage(), MESSAGE_MAX);
            String details = request.hasDetails() ? v.json("details", request.getDetails()) : null;
            v.throwIfAny();
            return workers.fail(id, workerId, message, details, request.getRetryable());
        });
    }

    private static Integer leaseSeconds(Violations v, boolean present, int value) {
        return v.range("lease_seconds", present, value, LEASE_SECONDS_MIN, LEASE_SECONDS_MAX, null);
    }
}
