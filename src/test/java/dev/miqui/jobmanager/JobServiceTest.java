package dev.miqui.jobmanager;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import dev.miqui.jobmanager.v1.CancelJobRequest;
import dev.miqui.jobmanager.v1.CompleteJobRequest;
import dev.miqui.jobmanager.v1.DeleteJobRequest;
import dev.miqui.jobmanager.v1.GetJobRequest;
import dev.miqui.jobmanager.v1.HeartbeatRequest;
import dev.miqui.jobmanager.v1.Job;
import dev.miqui.jobmanager.v1.JobEvent;
import dev.miqui.jobmanager.v1.JobState;
import dev.miqui.jobmanager.v1.JobType;
import dev.miqui.jobmanager.v1.ListJobEventsRequest;
import dev.miqui.jobmanager.v1.ListJobTypesRequest;
import dev.miqui.jobmanager.v1.ListJobsRequest;
import io.grpc.Status;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JobServiceTest extends GrpcTestSupport {

    // ---- CreateJob -----------------------------------------------------------------------------

    @Test
    void createQueuesAJobWithTheTypeDefaults() {
        Job job = jobs.createJob(newJob("report.generate")
                .setName("  Monthly report  ")
                .setSpec(struct("template", "monthly"))
                .putLabels("team", "finance")
                .build());

        assertThat(job.getId()).isNotBlank();
        assertThat(job.getName()).isEqualTo("Monthly report");
        assertThat(job.getState()).isEqualTo(JobState.JOB_STATE_QUEUED);
        assertThat(job.getPriority()).isZero();
        assertThat(job.getMaxAttempts()).as("report.generate's default").isEqualTo(5);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getVersion()).isZero();
        assertThat(job.getSpec()).isEqualTo(struct("template", "monthly"));
        assertThat(job.getLabelsMap()).containsEntry("team", "finance");
        assertThat(job.getRunAfter()).isEqualTo(job.getCreatedAt());
        assertThat(job.hasResult()).isFalse();
        assertThat(job.hasError()).isFalse();
        assertThat(job.getLeaseOwner()).isEmpty();
        assertThat(job.hasStartedAt()).isFalse();

        var events = jobs.listJobEvents(ListJobEventsRequest.newBuilder().setJobId(job.getId()).build());
        assertThat(events.getItemsList()).singleElement().satisfies(e -> {
            assertThat(e.getFromState()).isEqualTo(JobState.JOB_STATE_UNSPECIFIED);
            assertThat(e.getToState()).isEqualTo(JobState.JOB_STATE_QUEUED);
            assertThat(e.getActor()).isEqualTo("api");
        });
    }

    @Test
    void createKeepsExplicitOptionsAndNestedJson() {
        Instant later = Instant.now().plus(Duration.ofHours(1));
        Struct spec = Struct.newBuilder()
                .putFields("n", Value.newBuilder().setNumberValue(42).build())
                .putFields("flag", Value.newBuilder().setBoolValue(true).build())
                .putFields("list", Value.newBuilder().setListValue(ListValue.newBuilder()
                        .addValues(Value.newBuilder().setStringValue("a"))).build())
                .putFields("nested", Value.newBuilder().setStructValue(struct("k", "v")).build())
                .build();

        Job job = jobs.createJob(newJob("demo.echo").setSpec(spec).setPriority(7).setMaxAttempts(1)
                .setRunAfter(Timestamp.newBuilder().setSeconds(later.getEpochSecond()))
                .setIdempotencyKey("order-1").build());

        assertThat(job.getSpec()).isEqualTo(spec);
        assertThat(job.getPriority()).isEqualTo(7);
        assertThat(job.getMaxAttempts()).isEqualTo(1);
        assertThat(job.getRunAfter().getSeconds()).isEqualTo(later.getEpochSecond());
        assertThat(job.getIdempotencyKey()).isEqualTo("order-1");
    }

    @Test
    void anIdempotencyKeyThatWasUsedReturnsTheFirstJob() {
        Job first = jobs.createJob(newJob("demo.echo").setIdempotencyKey("same").build());
        Job second = jobs.createJob(newJob("demo.sleep").setIdempotencyKey(" same ").build());

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job_events")).as("no second creation event").isEqualTo(1);
    }

    @Test
    void createReportsEveryInvalidFieldAtOnce() {
        var error = rpcError(() -> jobs.createJob(newJob("no.such.type")
                .setName(" ")
                .putLabels("Bad Key", "x")
                .setPriority(10)
                .setMaxAttempts(0)
                .setRunAfter(Timestamp.newBuilder().setSeconds(Instant.now().plus(Duration.ofDays(31)).getEpochSecond()))
                .setIdempotencyKey("k".repeat(101))
                .build()));

        assertThat(error.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(error.reason()).isEqualTo("BAD_USER_INPUT");
        assertThat(error.violations()).containsOnlyKeys("name", "type", "labels.Bad Key", "priority", "max_attempts",
                "run_after", "idempotency_key");
        assertThat(error.violations().get("type")).contains("not a registered job type");
    }

    @Test
    void specIsRequiredAndMustBeStorableJson() {
        assertThat(rpcError(() -> jobs.createJob(newJob("demo.echo").clearSpec().build())).violations())
                .containsOnlyKeys("spec");
        assertThat(rpcError(() -> jobs.createJob(newJob("demo.echo").setSpec(struct("k", "nul\0")).build())).violations())
                .containsEntry("spec", "spec cannot contain NUL characters");
        assertThat(rpcError(() -> jobs.createJob(newJob("demo.echo").setSpec(struct("k\0", "v")).build())).violations())
                .containsEntry("spec", "spec cannot contain NUL characters");
        Struct nan = Struct.newBuilder().putFields("list", Value.newBuilder().setListValue(ListValue.newBuilder()
                .addValues(Value.newBuilder().setNumberValue(Double.NaN))).build()).build();
        assertThat(rpcError(() -> jobs.createJob(newJob("demo.echo").setSpec(nan).build())).violations())
                .containsEntry("spec", "spec cannot contain NaN or Infinity");
        Struct big = struct("blob", "x".repeat(33 * 1024));
        assertThat(rpcError(() -> jobs.createJob(newJob("demo.echo").setSpec(big).build())).violations())
                .containsEntry("spec", "spec cannot exceed 32768 bytes as JSON");
    }

    @Test
    void labelsAreBoundedInCountAndSize() {
        var tooMany = newJob("demo.echo");
        for (int i = 0; i < 17; i++) {
            tooMany.putLabels("k" + i, "v");
        }
        assertThat(rpcError(() -> jobs.createJob(tooMany.build())).violations()).containsOnlyKeys("labels");
        assertThat(rpcError(() -> jobs.createJob(newJob("demo.echo").putLabels("k", "v".repeat(64)).build()))
                .violations()).containsOnlyKeys("labels.k");
        assertThat(rpcError(() -> jobs.createJob(newJob("demo.echo").putLabels("k", "\0").build()))
                .violations()).containsOnlyKeys("labels.k");
    }

    @Test
    void anInvalidTimestampIsRejected() {
        var error = rpcError(() -> jobs.createJob(newJob("demo.echo")
                .setRunAfter(Timestamp.newBuilder().setSeconds(1).setNanos(-1)).build()));
        assertThat(error.violations()).containsEntry("run_after", "run_after must be a valid timestamp");
    }

    // ---- GetJob --------------------------------------------------------------------------------

    @Test
    void getReturnsTheJobAndCachesNothingWhileItCanStillChange() {
        Job job = createJob();

        assertThat(jobs.getJob(GetJobRequest.newBuilder().setId(job.getId()).build())).isEqualTo(job);
        assertThat(cache.entries).isEmpty();
        assertThat(cache.calls).noneMatch(call -> call.startsWith("lock"));
    }

    @Test
    void aFinishedJobIsCachedOnceAndThenServedFromTheCache() {
        Job job = createJob();
        claim("w1");
        workers.completeJob(CompleteJobRequest.newBuilder().setJobId(job.getId()).setWorkerId("w1").build());
        UUID id = UUID.fromString(job.getId());

        Job first = jobs.getJob(GetJobRequest.newBuilder().setId(job.getId()).build());
        assertThat(cache.calls).containsExactly("get " + id, "lock " + id, "put " + id, "unlock " + id);

        // Served without touching the database: a row change behind the cache's back isn't seen.
        sql("UPDATE jobs SET name = 'changed behind the cache' WHERE id = ?", id);
        cache.calls.clear();
        Job second = jobs.getJob(GetJobRequest.newBuilder().setId(job.getId()).build());
        assertThat(second).isEqualTo(first);
        assertThat(second.getState()).isEqualTo(JobState.JOB_STATE_SUCCEEDED);
        assertThat(cache.calls).containsExactly("get " + id);
    }

    @Test
    void aJobDeletedBetweenTheReadAndTheFillIsNotCached() {
        Job job = createJob();
        jobs.cancelJob(CancelJobRequest.newBuilder().setId(job.getId()).build());
        UUID id = UUID.fromString(job.getId());
        // GetJob has read the (terminal) job and is about to take the lock when it is deleted.
        cache.beforeLock = () -> sql("DELETE FROM jobs WHERE id = ?", id);

        var error = rpcError(() -> jobs.getJob(GetJobRequest.newBuilder().setId(job.getId()).build()));

        assertThat(error.code()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(cache.entries).doesNotContainKey(id);
    }

    @Test
    void getRejectsAMalformedIdAndReportsAnUnknownOne() {
        var bad = rpcError(() -> jobs.getJob(GetJobRequest.newBuilder().setId("1-2-3-4-5").build()));
        assertThat(bad.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(bad.violations()).containsOnlyKeys("id");

        var missing = rpcError(() -> jobs.getJob(GetJobRequest.newBuilder().setId(UUID.randomUUID().toString()).build()));
        assertThat(missing.code()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(missing.reason()).isEqualTo("NOT_FOUND");
    }

    // ---- ListJobs ------------------------------------------------------------------------------

    @Test
    void listIsNewestFirstAndPaginates() {
        Job a = createJob();
        Job b = createJob();
        Job c = createJob();

        var page = jobs.listJobs(ListJobsRequest.newBuilder().setLimit(2).build());
        assertThat(page.getItemsList()).extracting(Job::getId).containsExactly(c.getId(), b.getId());
        assertThat(page.getTotalCount()).isEqualTo(3);

        var next = jobs.listJobs(ListJobsRequest.newBuilder().setLimit(2).setOffset(2).build());
        assertThat(next.getItemsList()).extracting(Job::getId).containsExactly(a.getId());
    }

    @Test
    void listFiltersByStateTypeAndLabels() {
        Job echo = jobs.createJob(newJob("demo.echo").putLabels("team", "a").putLabels("env", "dev").build());
        Job sleep = jobs.createJob(newJob("demo.sleep").putLabels("team", "b").build());
        claim("w1", "demo.sleep");

        assertThat(jobs.listJobs(ListJobsRequest.newBuilder().addStates(JobState.JOB_STATE_RUNNING).build())
                .getItemsList()).extracting(Job::getId).containsExactly(sleep.getId());
        assertThat(jobs.listJobs(ListJobsRequest.newBuilder().addStates(JobState.JOB_STATE_QUEUED)
                .addStates(JobState.JOB_STATE_RUNNING).build()).getTotalCount()).isEqualTo(2);
        assertThat(jobs.listJobs(ListJobsRequest.newBuilder().setType("demo.echo").build())
                .getItemsList()).extracting(Job::getId).containsExactly(echo.getId());
        var byLabel = jobs.listJobs(ListJobsRequest.newBuilder().putLabels("team", "a").build());
        assertThat(byLabel.getItemsList()).extracting(Job::getId).containsExactly(echo.getId());
        assertThat(byLabel.getTotalCount()).isEqualTo(1);
        assertThat(jobs.listJobs(ListJobsRequest.newBuilder().putLabels("team", "a").putLabels("env", "prod").build())
                .getItemsList()).isEmpty();
    }

    @Test
    void listRejectsOutOfRangePagingAndUnspecifiedStates() {
        var error = rpcError(() -> jobs.listJobs(ListJobsRequest.newBuilder().setLimit(0).setOffset(-1)
                .addStates(JobState.JOB_STATE_UNSPECIFIED).putLabels("UPPER", "x").build()));
        assertThat(error.violations()).containsOnlyKeys("limit", "offset", "states", "labels.UPPER");
        assertThat(rpcError(() -> jobs.listJobs(ListJobsRequest.newBuilder().setLimit(201).build())).violations())
                .containsEntry("limit", "limit must be between 1 and 200");
        assertThat(rpcError(() -> jobs.listJobs(ListJobsRequest.newBuilder().addStatesValue(99).build())).violations())
                .containsOnlyKeys("states");
    }

    // ---- CancelJob -----------------------------------------------------------------------------

    @Test
    void cancelAQueuedJob() {
        Job job = createJob();

        Job cancelled = jobs.cancelJob(CancelJobRequest.newBuilder().setId(job.getId()).setVersion(0)
                .setReason("no longer needed").build());

        assertThat(cancelled.getState()).isEqualTo(JobState.JOB_STATE_CANCELLED);
        assertThat(cancelled.getVersion()).isEqualTo(1);
        assertThat(cancelled.hasFinishedAt()).isTrue();
        JobEvent event = jobs.listJobEvents(ListJobEventsRequest.newBuilder().setJobId(job.getId()).build()).getItems(1);
        assertThat(event.getFromState()).isEqualTo(JobState.JOB_STATE_QUEUED);
        assertThat(event.getToState()).isEqualTo(JobState.JOB_STATE_CANCELLED);
        assertThat(event.getDetail()).isEqualTo(struct("reason", "no longer needed"));
    }

    @Test
    void cancellingARunningJobTakesTheLeaseFromItsWorker() {
        Job job = createJob();
        claim("w1");

        Job cancelled = jobs.cancelJob(CancelJobRequest.newBuilder().setId(job.getId()).build());
        assertThat(cancelled.getLeaseOwner()).isEmpty();
        assertThat(cancelled.hasLeaseExpiresAt()).isFalse();

        var error = rpcError(() -> workers.heartbeat(HeartbeatRequest.newBuilder()
                .setJobId(job.getId()).setWorkerId("w1").build()));
        assertThat(error.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(error.reason()).isEqualTo("LEASE_NOT_HELD");
        assertThat(error.description()).contains("CANCELLED");
        JobEvent event = jobs.listJobEvents(ListJobEventsRequest.newBuilder().setJobId(job.getId()).build()).getItems(2);
        assertThat(event.getDetail()).isEqualTo(struct("worker_id", "w1"));
    }

    @Test
    void cancelRefusesAFinishedJobAStaleVersionAndAnUnknownId() {
        Job job = createJob();

        var stale = rpcError(() -> jobs.cancelJob(CancelJobRequest.newBuilder().setId(job.getId()).setVersion(3).build()));
        assertThat(stale.code()).isEqualTo(Status.Code.ABORTED);
        assertThat(stale.reason()).isEqualTo("CONFLICT");

        jobs.cancelJob(CancelJobRequest.newBuilder().setId(job.getId()).build());
        var done = rpcError(() -> jobs.cancelJob(CancelJobRequest.newBuilder().setId(job.getId()).build()));
        assertThat(done.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(done.reason()).isEqualTo("CONFLICT");

        var missing = rpcError(() -> jobs.cancelJob(CancelJobRequest.newBuilder()
                .setId(UUID.randomUUID().toString()).build()));
        assertThat(missing.code()).isEqualTo(Status.Code.NOT_FOUND);

        var bad = rpcError(() -> jobs.cancelJob(CancelJobRequest.newBuilder().setId("x").setVersion(-1)
                .setReason("r".repeat(501)).build()));
        assertThat(bad.violations()).containsOnlyKeys("id", "version", "reason");
    }

    // ---- DeleteJob -----------------------------------------------------------------------------

    @Test
    void deleteRemovesAFinishedJobItsEventsAndItsCacheEntry() {
        Job job = createJob();
        jobs.cancelJob(CancelJobRequest.newBuilder().setId(job.getId()).build());
        jobs.getJob(GetJobRequest.newBuilder().setId(job.getId()).build());
        UUID id = UUID.fromString(job.getId());
        assertThat(cache.entries).containsKey(id);

        jobs.deleteJob(DeleteJobRequest.newBuilder().setId(job.getId()).build());

        assertThat(cache.entries).doesNotContainKey(id);
        assertThat(count("SELECT count(*) FROM job_events WHERE job_id = ?", id)).isZero();
        assertThat(rpcError(() -> jobs.getJob(GetJobRequest.newBuilder().setId(job.getId()).build())).code())
                .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void deleteRefusesAnUnfinishedJobAndReportsAnUnknownOne() {
        Job job = createJob();

        var running = rpcError(() -> jobs.deleteJob(DeleteJobRequest.newBuilder().setId(job.getId()).build()));
        assertThat(running.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(running.description()).contains("cancel it first");

        assertThat(rpcError(() -> jobs.deleteJob(DeleteJobRequest.newBuilder()
                .setId(UUID.randomUUID().toString()).build())).code()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(rpcError(() -> jobs.deleteJob(DeleteJobRequest.newBuilder().setId("").build())).violations())
                .containsOnlyKeys("id");
    }

    // ---- ListJobEvents / ListJobTypes ----------------------------------------------------------

    @Test
    void eventsAreOldestFirstAndPaginate() {
        Job job = createJob();
        claim("w1");
        workers.completeJob(CompleteJobRequest.newBuilder().setJobId(job.getId()).setWorkerId("w1").build());

        var all = jobs.listJobEvents(ListJobEventsRequest.newBuilder().setJobId(job.getId()).build());
        assertThat(all.getItemsList()).extracting(JobEvent::getToState).containsExactly(
                JobState.JOB_STATE_QUEUED, JobState.JOB_STATE_RUNNING, JobState.JOB_STATE_SUCCEEDED);
        assertThat(all.getItemsList()).extracting(JobEvent::getActor).containsExactly("api", "worker:w1", "worker:w1");
        assertThat(all.getTotalCount()).isEqualTo(3);

        var page = jobs.listJobEvents(ListJobEventsRequest.newBuilder().setJobId(job.getId()).setLimit(1).setOffset(1).build());
        assertThat(page.getItemsList()).extracting(JobEvent::getToState).containsExactly(JobState.JOB_STATE_RUNNING);
    }

    @Test
    void eventsOfAnUnknownJobAreNotFound() {
        assertThat(rpcError(() -> jobs.listJobEvents(ListJobEventsRequest.newBuilder()
                .setJobId(UUID.randomUUID().toString()).build())).code()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(rpcError(() -> jobs.listJobEvents(ListJobEventsRequest.newBuilder().setJobId("x").setLimit(500)
                .build())).violations()).containsOnlyKeys("job_id", "limit");
    }

    @Test
    void listJobTypesReturnsTheRegistry() {
        var types = jobs.listJobTypes(ListJobTypesRequest.getDefaultInstance()).getItemsList();
        assertThat(types).extracting(JobType::getName)
                .containsExactly("demo.echo", "demo.sleep", "email.send", "report.generate");
        assertThat(types.getFirst().getDefaultLeaseSeconds()).isEqualTo(30);
    }
}
