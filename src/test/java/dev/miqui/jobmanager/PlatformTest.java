package dev.miqui.jobmanager;

import dev.miqui.jobmanager.service.JobStats;
import dev.miqui.jobmanager.v1.GetJobRequest;
import io.grpc.Status;
import grpc.health.v1.HealthOuterClass.HealthCheckRequest;
import grpc.health.v1.HealthOuterClass.HealthCheckResponse;
import grpc.health.v1.HealthGrpc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Probes, gRPC health, the inbound size limit and the metrics plumbing. */
@QuarkusTest
class PlatformTest extends GrpcTestSupport {

    @ConfigProperty(name = "quarkus.management.test-port")
    int managementPort;

    @Inject
    SimpleMeterRegistry registry;

    @Inject
    JobStats stats;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> probe(String name) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + managementPort + "/q/health/" + name)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void livenessIsUpWhileTheCacheClientRuns() throws Exception {
        var response = probe("live");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hazelcastClient").contains("\"status\": \"UP\"");
    }

    @Test
    void livenessIsDownIfTheCacheClientHasShutDown() throws Exception {
        // A Hazelcast client that exhausted its connect timeout shuts down for good; only a
        // container restart recovers, so liveness must fail (see CacheClientHealthCheck).
        cache.connected = false;
        var response = probe("live");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("cache client shut down");
    }

    @Test
    void readinessIsUpOnceStarted() throws Exception {
        assertThat(probe("ready").statusCode()).isEqualTo(200);
    }

    @Test
    void grpcHealthServiceIsServing() {
        var response = HealthGrpc.newBlockingStub(channel).check(HealthCheckRequest.getDefaultInstance());
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
    }

    @Test
    void anOversizedRequestIsRejectedBeforeTheServiceRuns() {
        var request = newJob("demo.echo").setSpec(struct("blob", "x".repeat(70 * 1024))).build();
        assertThat(rpcError(() -> jobs.createJob(request)).code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    }

    @Test
    void callsAreTimedAndFailedCallsCountedByErrorCode() {
        String[] tags = {"rpc_service", "job.v1.JobService", "rpc_method", "GetJob",
                "grpc_status_code", "NOT_FOUND", "error_code", "NOT_FOUND"};
        Counter before = registry.find("grpc.errors").tags(tags).counter();
        double initial = before == null ? 0 : before.count();

        rpcError(() -> jobs.getJob(GetJobRequest.newBuilder().setId(UUID.randomUUID().toString()).build()));

        assertThat(registry.get("grpc.errors").tags(tags).counter().count()).isEqualTo(initial + 1);
        Timer timer = registry.get("grpc.server").tags("rpc_service", "job.v1.JobService", "rpc_method", "GetJob",
                "grpc_status_code", "NOT_FOUND").timer();
        assertThat(timer.count()).isPositive();
    }

    @Test
    void healthChecksAreNotTimed() {
        HealthGrpc.newBlockingStub(channel).check(HealthCheckRequest.getDefaultInstance());
        assertThat(registry.find("grpc.server").tag("rpc_service", "grpc.health.v1.Health").timer()).isNull();
    }

    @Test
    void jobTransitionsAndClaimsAreCounted() {
        double created = counter("job.transitions", "from_state", "NONE", "to_state", "QUEUED");
        double empty = counter("job.claims", "outcome", "empty");
        createJob();
        workers.claimJob(dev.miqui.jobmanager.v1.ClaimJobRequest.newBuilder().setWorkerId("w").addTypes("demo.sleep").build());
        claim("w");

        assertThat(counter("job.transitions", "from_state", "NONE", "to_state", "QUEUED")).isEqualTo(created + 1);
        assertThat(counter("job.claims", "outcome", "empty")).isEqualTo(empty + 1);
        assertThat(registry.get("job.queue.wait").timer().count()).isPositive();
    }

    @Test
    void sqlStatementsAreTimed() {
        long before = registry.find("jdbc.query").tag("jdbc_operation", "update").timers().stream()
                .mapToLong(Timer::count).sum();
        createJob();
        assertThat(registry.get("jdbc.query").tag("jdbc_operation", "update").timer().count()).isGreaterThan(before);
        assertThat(registry.get("jdbc.query").tag("jdbc_operation", "query").timer().count()).isPositive();
    }

    @Test
    void theJobsGaugeReportsCountsPerState() {
        createJob();
        createJob();
        claim("w");

        stats.refresh();

        assertThat(registry.get("jobs").tag("state", "QUEUED").gauge().value()).isEqualTo(1);
        assertThat(registry.get("jobs").tag("state", "RUNNING").gauge().value()).isEqualTo(1);
        assertThat(registry.get("jobs").tag("state", "FAILED").gauge().value()).isZero();
    }

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }
}
