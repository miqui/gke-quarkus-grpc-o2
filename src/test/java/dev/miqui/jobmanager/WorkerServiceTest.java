package dev.miqui.jobmanager;

import dev.miqui.jobmanager.service.LeaseReaper;
import dev.miqui.jobmanager.v1.ClaimJobRequest;
import dev.miqui.jobmanager.v1.CompleteJobRequest;
import dev.miqui.jobmanager.v1.FailJobRequest;
import dev.miqui.jobmanager.v1.GetJobRequest;
import dev.miqui.jobmanager.v1.HeartbeatRequest;
import dev.miqui.jobmanager.v1.Job;
import dev.miqui.jobmanager.v1.JobEvent;
import dev.miqui.jobmanager.v1.JobState;
import dev.miqui.jobmanager.v1.ListJobEventsRequest;
import io.grpc.Status;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class WorkerServiceTest extends GrpcTestSupport {

    @Inject
    LeaseReaper reaper;

    private static long seconds(com.google.protobuf.Timestamp t) {
        return t.getSeconds();
    }

    // ---- ClaimJob ------------------------------------------------------------------------------

    @Test
    void claimLeasesTheJobToTheWorker() {
        Job created = createJob();
        long before = Instant.now().getEpochSecond();

        Job job = claim("worker-1");

        assertThat(job.getId()).isEqualTo(created.getId());
        assertThat(job.getState()).isEqualTo(JobState.JOB_STATE_RUNNING);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getVersion()).isEqualTo(1);
        assertThat(job.getLeaseOwner()).isEqualTo("worker-1");
        // demo.echo's default lease is 30s.
        assertThat(seconds(job.getLeaseExpiresAt())).isBetween(before + 28, before + 32);
        assertThat(job.hasStartedAt()).isTrue();
        JobEvent event = jobs.listJobEvents(ListJobEventsRequest.newBuilder().setJobId(job.getId()).build()).getItems(1);
        assertThat(event.getActor()).isEqualTo("worker:worker-1");
        assertThat(event.getFromState()).isEqualTo(JobState.JOB_STATE_QUEUED);
    }

    @Test
    void claimReturnsNothingWhenNoJobIsReady() {
        createJob("demo.sleep");
        jobs.createJob(newJob("demo.echo").setRunAfter(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(Instant.now().plus(Duration.ofHours(1)).getEpochSecond())).build());

        var response = workers.claimJob(ClaimJobRequest.newBuilder().setWorkerId("w").addTypes("demo.echo").build());

        assertThat(response.hasJob()).as("other type, or not yet due").isFalse();
    }

    @Test
    void claimTakesTheHighestPriorityThenTheOldest() {
        Job low = createJob();
        Job oldHigh = jobs.createJob(newJob("demo.echo").setPriority(5).build());
        Job newHigh = jobs.createJob(newJob("demo.echo").setPriority(5).build());

        assertThat(claim("w").getId()).isEqualTo(oldHigh.getId());
        assertThat(claim("w").getId()).isEqualTo(newHigh.getId());
        assertThat(claim("w").getId()).isEqualTo(low.getId());
    }

    @Test
    void claimHonoursAnExplicitLease() {
        createJob();
        long before = Instant.now().getEpochSecond();
        Job job = workers.claimJob(ClaimJobRequest.newBuilder().setWorkerId("w").addTypes("demo.echo")
                .setLeaseSeconds(600).build()).getJob();
        assertThat(seconds(job.getLeaseExpiresAt())).isBetween(before + 598, before + 602);
    }

    @Test
    void concurrentClaimsNeverGetTheSameJob() throws Exception {
        int jobsCount = 20;
        for (int i = 0; i < jobsCount; i++) {
            createJob();
        }
        List<Callable<String>> claimers = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String worker = "w" + i;
            claimers.add(() -> {
                var response = workers.claimJob(ClaimJobRequest.newBuilder().setWorkerId(worker).addTypes("demo.echo").build());
                return response.hasJob() ? response.getJob().getId() : null;
            });
        }
        List<String> claimed = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(16)) {
            for (Future<String> result : pool.invokeAll(claimers)) {
                if (result.get() != null) {
                    claimed.add(result.get());
                }
            }
        }
        Set<String> distinct = new HashSet<>(claimed);
        assertThat(claimed).hasSize(jobsCount);
        assertThat(distinct).hasSize(jobsCount);
    }

    @Test
    void claimValidatesItsInput() {
        var error = rpcError(() -> workers.claimJob(ClaimJobRequest.newBuilder().setWorkerId("has space")
                .setLeaseSeconds(4).build()));
        assertThat(error.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(error.violations()).containsOnlyKeys("worker_id", "types", "lease_seconds");

        var unknown = rpcError(() -> workers.claimJob(ClaimJobRequest.newBuilder().setWorkerId("w")
                .addTypes("demo.echo").addTypes("nope").setLeaseSeconds(3601).build()));
        assertThat(unknown.violations()).containsOnlyKeys("types[1]", "lease_seconds");

        var tooMany = ClaimJobRequest.newBuilder().setWorkerId("w");
        for (int i = 0; i < 21; i++) {
            tooMany.addTypes("demo.echo");
        }
        assertThat(rpcError(() -> workers.claimJob(tooMany.build())).violations()).containsOnlyKeys("types");
    }

    // ---- Heartbeat -----------------------------------------------------------------------------

    @Test
    void heartbeatExtendsTheLeaseWithoutAStateChange() {
        Job job = createJob();
        claim("w1");
        long before = Instant.now().getEpochSecond();

        Job beat = workers.heartbeat(HeartbeatRequest.newBuilder().setJobId(job.getId()).setWorkerId("w1")
                .setLeaseSeconds(300).build());

        assertThat(seconds(beat.getLeaseExpiresAt())).isBetween(before + 298, before + 302);
        assertThat(beat.getVersion()).as("no version bump").isEqualTo(1);
        Job defaulted = workers.heartbeat(HeartbeatRequest.newBuilder().setJobId(job.getId()).setWorkerId("w1").build());
        assertThat(seconds(defaulted.getLeaseExpiresAt())).isBetween(before + 28, before + 32);
        assertThat(jobs.listJobEvents(ListJobEventsRequest.newBuilder().setJobId(job.getId()).build())
                .getTotalCount()).isEqualTo(2);
    }

    @Test
    void onlyTheWorkerHoldingTheLeaseCanUseIt() {
        Job job = createJob();
        claim("w1");

        var other = rpcError(() -> workers.heartbeat(HeartbeatRequest.newBuilder().setJobId(job.getId())
                .setWorkerId("w2").build()));
        assertThat(other.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(other.reason()).isEqualTo("LEASE_NOT_HELD");
        assertThat(other.description()).contains("leased to another worker");

        var complete = rpcError(() -> workers.completeJob(CompleteJobRequest.newBuilder().setJobId(job.getId())
                .setWorkerId("w2").build()));
        assertThat(complete.reason()).isEqualTo("LEASE_NOT_HELD");
    }

    @Test
    void workerCallsOnAQueuedOrUnknownJobAreRefused() {
        Job job = createJob();
        assertThat(rpcError(() -> workers.failJob(FailJobRequest.newBuilder().setJobId(job.getId())
                .setWorkerId("w1").setMessage("boom").build())).description()).contains("QUEUED");
        assertThat(rpcError(() -> workers.heartbeat(HeartbeatRequest.newBuilder()
                .setJobId(UUID.randomUUID().toString()).setWorkerId("w1").build())).code())
                .isEqualTo(Status.Code.NOT_FOUND);
        assertThat(rpcError(() -> workers.heartbeat(HeartbeatRequest.newBuilder().setJobId("x").setWorkerId("")
                .setLeaseSeconds(0).build())).violations()).containsOnlyKeys("job_id", "worker_id", "lease_seconds");
    }

    // ---- CompleteJob / FailJob -----------------------------------------------------------------

    @Test
    void completeStoresTheResultAndReleasesTheLease() {
        Job job = createJob();
        claim("w1");

        Job done = workers.completeJob(CompleteJobRequest.newBuilder().setJobId(job.getId()).setWorkerId("w1")
                .setResult(struct("url", "gs://bucket/report.pdf")).build());

        assertThat(done.getState()).isEqualTo(JobState.JOB_STATE_SUCCEEDED);
        assertThat(done.getResult()).isEqualTo(struct("url", "gs://bucket/report.pdf"));
        assertThat(done.getLeaseOwner()).isEmpty();
        assertThat(done.hasFinishedAt()).isTrue();
        assertThat(done.getVersion()).isEqualTo(2);
    }

    @Test
    void completeWithoutAResultStoresAnEmptyObjectAndValidatesTheResult() {
        Job job = createJob();
        claim("w1");
        assertThat(rpcError(() -> workers.completeJob(CompleteJobRequest.newBuilder().setJobId(job.getId())
                .setWorkerId("w1").setResult(struct("k", "\0")).build())).violations()).containsOnlyKeys("result");

        Job done = workers.completeJob(CompleteJobRequest.newBuilder().setJobId(job.getId()).setWorkerId("w1").build());
        assertThat(done.hasResult()).isTrue();
        assertThat(done.getResult().getFieldsCount()).isZero();
    }

    @Test
    void aRetryableFailureRequeuesWithABackoff() {
        Job job = createJob();
        claim("w1");
        long before = Instant.now().getEpochSecond();

        Job failed = workers.failJob(FailJobRequest.newBuilder().setJobId(job.getId()).setWorkerId("w1")
                .setMessage("upstream timeout").setDetails(struct("host", "smtp")).setRetryable(true).build());

        assertThat(failed.getState()).isEqualTo(JobState.JOB_STATE_QUEUED);
        assertThat(failed.getError().getMessage()).isEqualTo("upstream timeout");
        assertThat(failed.getError().getDetails()).isEqualTo(struct("host", "smtp"));
        assertThat(failed.getError().getRetryable()).isTrue();
        assertThat(failed.getLeaseOwner()).isEmpty();
        assertThat(failed.hasFinishedAt()).isFalse();
        // First retry after 5s: not claimable yet.
        assertThat(seconds(failed.getRunAfter())).isBetween(before + 4, before + 6);
        assertThat(workers.claimJob(ClaimJobRequest.newBuilder().setWorkerId("w2").addTypes("demo.echo").build())
                .hasJob()).isFalse();

        sql("UPDATE jobs SET run_after = now() WHERE id = ?", UUID.fromString(job.getId()));
        Job retried = claim("w2");
        assertThat(retried.getAttempts()).isEqualTo(2);
        assertThat(retried.getError().getMessage()).as("kept until the job succeeds or fails again")
                .isEqualTo("upstream timeout");
    }

    @Test
    void aFailureWithNoAttemptsLeftOrNotRetryableIsFinal() {
        Job once = jobs.createJob(newJob("demo.echo").setMaxAttempts(1).build());
        claim("w1");
        Job exhausted = workers.failJob(FailJobRequest.newBuilder().setJobId(once.getId()).setWorkerId("w1")
                .setMessage("boom").setRetryable(true).build());
        assertThat(exhausted.getState()).isEqualTo(JobState.JOB_STATE_FAILED);
        assertThat(exhausted.hasFinishedAt()).isTrue();

        Job other = createJob();
        claim("w1");
        Job fatal = workers.failJob(FailJobRequest.newBuilder().setJobId(other.getId()).setWorkerId("w1")
                .setMessage("bad input").build());
        assertThat(fatal.getState()).isEqualTo(JobState.JOB_STATE_FAILED);
        assertThat(fatal.getError().getRetryable()).isFalse();
        assertThat(fatal.getError().hasDetails()).isFalse();
    }

    @Test
    void failValidatesItsInput() {
        var error = rpcError(() -> workers.failJob(FailJobRequest.newBuilder().setJobId("x").setWorkerId("w")
                .setMessage(" ").setDetails(struct("k", "\0")).build()));
        assertThat(error.violations()).containsOnlyKeys("job_id", "message", "details");
    }

    // ---- lease reaper --------------------------------------------------------------------------

    @Test
    void anExpiredLeaseIsRequeuedWhileAttemptsRemain() {
        Job job = createJob();
        claim("w1");
        expireLease(job.getId());

        assertThat(reaper.reap()).isEqualTo(1);

        Job requeued = jobs.getJob(GetJobRequest.newBuilder().setId(job.getId()).build());
        assertThat(requeued.getState()).isEqualTo(JobState.JOB_STATE_QUEUED);
        assertThat(requeued.getError().getMessage()).isEqualTo("lease expired");
        assertThat(requeued.getError().getDetails()).isEqualTo(struct("worker_id", "w1"));
        assertThat(requeued.getLeaseOwner()).isEmpty();
        JobEvent event = jobs.listJobEvents(ListJobEventsRequest.newBuilder().setJobId(job.getId()).build()).getItems(2);
        assertThat(event.getActor()).isEqualTo("system:lease-reaper");
        assertThat(event.getToState()).isEqualTo(JobState.JOB_STATE_QUEUED);

        // The worker that lost it is told so; another can claim it at once.
        assertThat(rpcError(() -> workers.heartbeat(HeartbeatRequest.newBuilder().setJobId(job.getId())
                .setWorkerId("w1").build())).reason()).isEqualTo("LEASE_NOT_HELD");
        assertThat(claim("w2").getAttempts()).isEqualTo(2);
    }

    @Test
    void anExpiredLeaseWithNoAttemptsLeftFailsTheJob() {
        Job job = jobs.createJob(newJob("demo.echo").setMaxAttempts(1).build());
        claim("w1");
        expireLease(job.getId());

        reaper.reap();

        Job failed = jobs.getJob(GetJobRequest.newBuilder().setId(job.getId()).build());
        assertThat(failed.getState()).isEqualTo(JobState.JOB_STATE_FAILED);
        assertThat(failed.getError().getRetryable()).isFalse();
        assertThat(failed.hasFinishedAt()).isTrue();
    }

    @Test
    void theReaperLeavesLiveLeasesAloneAndWorksInBatches() {
        Job live = createJob();
        claim("w1");
        for (int i = 0; i < 150; i++) {
            createJob("demo.sleep");
        }
        sql("""
                UPDATE jobs SET state = 'RUNNING', attempts = 1, lease_owner = 'gone',
                                lease_expires_at = now() - interval '1 minute'
                WHERE type = 'demo.sleep'
                """);

        assertThat(reaper.reap()).isEqualTo(150);
        assertThat(reaper.reap()).isZero();
        assertThat(jobs.getJob(GetJobRequest.newBuilder().setId(live.getId()).build()).getState())
                .isEqualTo(JobState.JOB_STATE_RUNNING);
    }
}
