package dev.miqui.jobmanager;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import dev.miqui.jobmanager.repo.Db;
import dev.miqui.jobmanager.v1.ClaimJobRequest;
import dev.miqui.jobmanager.v1.CreateJobRequest;
import dev.miqui.jobmanager.v1.Job;
import dev.miqui.jobmanager.v1.JobServiceGrpc;
import dev.miqui.jobmanager.v1.WorkerServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The whole application (real gRPC server on the test port, so the inbound size limit and every
 * interceptor apply) against Dev Services Postgres, with the recording cache instead of Hazelcast.
 * Jobs and events are emptied before each test; the seeded job types stay.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class GrpcTestSupport {

    @ConfigProperty(name = "quarkus.grpc.server.test-port")
    int grpcPort;

    @Inject
    protected Db db;

    @Inject
    protected RecordingJobCache cache;

    protected ManagedChannel channel;
    protected JobServiceGrpc.JobServiceBlockingStub jobs;
    protected WorkerServiceGrpc.WorkerServiceBlockingStub workers;

    @BeforeAll
    void openChannel() {
        channel = ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build();
        jobs = JobServiceGrpc.newBlockingStub(channel);
        workers = WorkerServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    void closeChannel() {
        channel.shutdownNow();
    }

    @BeforeEach
    void emptyTables() {
        sql("TRUNCATE jobs CASCADE");
        cache.reset();
    }

    protected void sql(String statement, Object... params) {
        db.tx(c -> Db.update(c, statement, params));
    }

    protected long count(String query, Object... params) {
        return db.tx(c -> Db.count(c, query, params));
    }

    protected static Struct struct(String key, String value) {
        return Struct.newBuilder().putFields(key, Value.newBuilder().setStringValue(value).build()).build();
    }

    protected static CreateJobRequest.Builder newJob(String type) {
        return CreateJobRequest.newBuilder().setName("job-" + UUID.randomUUID()).setType(type)
                .setSpec(struct("input", "x"));
    }

    protected Job createJob(String type) {
        return jobs.createJob(newJob(type).build());
    }

    protected Job createJob() {
        return createJob("demo.echo");
    }

    protected Job claim(String worker, String... types) {
        var response = workers.claimJob(ClaimJobRequest.newBuilder().setWorkerId(worker)
                .addAllTypes(List.of(types.length == 0 ? new String[]{"demo.echo"} : types)).build());
        assertThat(response.hasJob()).as("a job was claimed").isTrue();
        return response.getJob();
    }

    /** Pretends the lease ran out without waiting for it. */
    protected void expireLease(String jobId) {
        sql("UPDATE jobs SET lease_expires_at = now() - interval '1 second' WHERE id = ?", UUID.fromString(jobId));
    }

    /** What a client can read from a failed call: status, ErrorInfo reason, field violations. */
    protected record RpcError(Status.Code code, String description, String reason, Map<String, String> violations) {
    }

    protected static RpcError rpcError(Executable call) {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, call);
        com.google.rpc.Status status = StatusProto.fromThrowable(e);
        String reason = null;
        Map<String, String> violations = new LinkedHashMap<>();
        if (status != null) {
            try {
                for (var detail : status.getDetailsList()) {
                    if (detail.is(ErrorInfo.class)) {
                        ErrorInfo info = detail.unpack(ErrorInfo.class);
                        assertThat(info.getDomain()).isEqualTo("job-manager-api.miqui.dev");
                        reason = info.getReason();
                    } else if (detail.is(BadRequest.class)) {
                        detail.unpack(BadRequest.class).getFieldViolationsList()
                                .forEach(v -> violations.put(v.getField(), v.getDescription()));
                    }
                }
            } catch (InvalidProtocolBufferException ex) {
                throw new AssertionError(ex);
            }
        }
        return new RpcError(e.getStatus().getCode(), e.getStatus().getDescription(), reason, violations);
    }
}
