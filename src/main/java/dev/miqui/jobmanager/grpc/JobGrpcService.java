package dev.miqui.jobmanager.grpc;

import com.google.protobuf.Empty;
import dev.miqui.jobmanager.repo.JobRepository;
import dev.miqui.jobmanager.repo.Json;
import dev.miqui.jobmanager.repo.Rows;
import dev.miqui.jobmanager.service.JobTypes;
import dev.miqui.jobmanager.service.Jobs;
import dev.miqui.jobmanager.v1.CancelJobRequest;
import dev.miqui.jobmanager.v1.CreateJobRequest;
import dev.miqui.jobmanager.v1.DeleteJobRequest;
import dev.miqui.jobmanager.v1.GetJobRequest;
import dev.miqui.jobmanager.v1.Job;
import dev.miqui.jobmanager.v1.JobServiceGrpc;
import dev.miqui.jobmanager.v1.JobState;
import dev.miqui.jobmanager.v1.ListJobEventsRequest;
import dev.miqui.jobmanager.v1.ListJobEventsResponse;
import dev.miqui.jobmanager.v1.ListJobTypesRequest;
import dev.miqui.jobmanager.v1.ListJobTypesResponse;
import dev.miqui.jobmanager.v1.ListJobsRequest;
import dev.miqui.jobmanager.v1.ListJobsResponse;
import dev.miqui.jobmanager.validation.Violations;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.RunOnVirtualThread;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.miqui.jobmanager.validation.Violations.IDEMPOTENCY_KEY_MAX;
import static dev.miqui.jobmanager.validation.Violations.MAX_ATTEMPTS_MAX;
import static dev.miqui.jobmanager.validation.Violations.NAME_MAX;
import static dev.miqui.jobmanager.validation.Violations.PRIORITY_MAX;
import static dev.miqui.jobmanager.validation.Violations.REASON_MAX;
import static dev.miqui.jobmanager.validation.Violations.TYPE_MAX;

/**
 * Validation and proto plumbing only; exceptions are mapped by {@link Rpc#unary}. Calls run on
 * virtual threads, so the blocking JDBC and Hazelcast calls below never block an event loop.
 */
@GrpcService
@RunOnVirtualThread
public class JobGrpcService extends JobServiceGrpc.JobServiceImplBase {

    private final Jobs jobs;
    private final JobTypes types;

    public JobGrpcService(Jobs jobs, JobTypes types) {
        this.jobs = jobs;
        this.types = types;
    }

    @Override
    public void createJob(CreateJobRequest request, StreamObserver<Job> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            String name = v.text("name", request.getName(), NAME_MAX);
            String type = v.registeredType("type", request.getType(), types.all().keySet());
            String spec = request.hasSpec() ? v.json("spec", request.getSpec()) : null;
            if (!request.hasSpec()) {
                v.add("spec", "spec is required (a JSON object, {} for none)");
            }
            v.labels("labels", request.getLabelsMap());
            int priority = v.range("priority", request.hasPriority(), request.getPriority(), 0, PRIORITY_MAX, 0);
            Integer maxAttempts = v.range("max_attempts", request.hasMaxAttempts(), request.getMaxAttempts(),
                    1, MAX_ATTEMPTS_MAX, null);
            Instant runAfter = v.runAfter("run_after", request.hasRunAfter(), request.getRunAfter(), Instant.now());
            String key = v.optionalText("idempotency_key", request.getIdempotencyKey(), IDEMPOTENCY_KEY_MAX);
            v.throwIfAny();
            return jobs.create(new JobRepository.NewJob(name, type, priority, spec,
                    Json.print(request.getLabelsMap()), maxAttempts, runAfter, key));
        });
    }

    @Override
    public void getJob(GetJobRequest request, StreamObserver<Job> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("id", request.getId());
            v.throwIfAny();
            return jobs.get(id);
        });
    }

    @Override
    public void listJobs(ListJobsRequest request, StreamObserver<ListJobsResponse> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            int limit = v.limit("limit", request.hasLimit(), request.getLimit());
            int offset = v.offset("offset", request.hasOffset(), request.getOffset());
            List<String> states = new ArrayList<>();
            for (JobState state : request.getStatesList()) {
                if (state == JobState.JOB_STATE_UNSPECIFIED || state == JobState.UNRECOGNIZED) {
                    v.add("states", "states must only contain QUEUED, RUNNING, SUCCEEDED, FAILED or CANCELLED");
                } else {
                    states.add(Rows.column(state));
                }
            }
            String type = v.optionalText("type", request.getType(), TYPE_MAX);
            v.labels("labels", request.getLabelsMap());
            v.throwIfAny();
            String labels = request.getLabelsCount() == 0 ? null : Json.print(request.getLabelsMap());
            return jobs.list(new JobRepository.Filter(states, type, labels), limit, offset);
        });
    }

    @Override
    public void cancelJob(CancelJobRequest request, StreamObserver<Job> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("id", request.getId());
            Integer version = v.range("version", request.hasVersion(), request.getVersion(), 0, Integer.MAX_VALUE, null);
            String reason = v.optionalText("reason", request.getReason(), REASON_MAX);
            v.throwIfAny();
            return jobs.cancel(id, version, reason);
        });
    }

    @Override
    public void deleteJob(DeleteJobRequest request, StreamObserver<Empty> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("id", request.getId());
            v.throwIfAny();
            jobs.delete(id);
            return Empty.getDefaultInstance();
        });
    }

    @Override
    public void listJobEvents(ListJobEventsRequest request, StreamObserver<ListJobEventsResponse> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("job_id", request.getJobId());
            int limit = v.limit("limit", request.hasLimit(), request.getLimit());
            int offset = v.offset("offset", request.hasOffset(), request.getOffset());
            v.throwIfAny();
            return jobs.events(id, limit, offset);
        });
    }

    @Override
    public void listJobTypes(ListJobTypesRequest request, StreamObserver<ListJobTypesResponse> response) {
        Rpc.unary(response, () -> ListJobTypesResponse.newBuilder().addAllItems(types.all().values()).build());
    }
}
