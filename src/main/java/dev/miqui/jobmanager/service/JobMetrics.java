package dev.miqui.jobmanager.service;

import com.google.protobuf.Timestamp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Job-level metrics, recorded after the transaction that caused them commits:
 * <ul>
 *   <li>{@code job.transitions{from_state, to_state}} - every state change (from_state "NONE" on
 *       creation);</li>
 *   <li>{@code job.claims{outcome}} - ClaimJob calls that got a job ("claimed") or none ("empty");</li>
 *   <li>{@code job.queue.wait} - from when a job became claimable (run_after) to its claim;</li>
 *   <li>{@code job.lease.expirations} - leases the reaper took back;</li>
 *   <li>{@code jobs{state}} - jobs per state, refreshed from the database by {@code JobStats}.</li>
 * </ul>
 */
@ApplicationScoped
public class JobMetrics {

    private static final String[] STATES = {"QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"};

    private final MeterRegistry registry;
    private final Timer queueWait;
    private final Map<String, AtomicLong> jobsByState = new ConcurrentHashMap<>();

    public JobMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.queueWait = Timer.builder("job.queue.wait")
                .description("Time from when a job became claimable to when a worker claimed it")
                .serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1),
                        Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60),
                        Duration.ofMinutes(5), Duration.ofMinutes(15))
                .register(registry);
        for (String state : STATES) {
            AtomicLong value = jobsByState.computeIfAbsent(state, s -> new AtomicLong());
            Gauge.builder("jobs", value, AtomicLong::doubleValue)
                    .description("Jobs per state (whole table, as of the last refresh)")
                    .tag("state", state)
                    .register(registry);
        }
    }

    public void transition(String from, String to) {
        Counter.builder("job.transitions")
                .description("Job state changes")
                .tag("from_state", from == null ? "NONE" : from)
                .tag("to_state", to)
                .register(registry)
                .increment();
    }

    public void claim(boolean claimed) {
        Counter.builder("job.claims")
                .description("ClaimJob calls, by whether a job was claimed")
                .tag("outcome", claimed ? "claimed" : "empty")
                .register(registry)
                .increment();
    }

    public void queueWait(Timestamp runAfter, Timestamp claimedAt) {
        long millis = (claimedAt.getSeconds() - runAfter.getSeconds()) * 1000
                + (claimedAt.getNanos() - runAfter.getNanos()) / 1_000_000;
        queueWait.record(Duration.ofMillis(Math.max(0, millis)));
    }

    public void leaseExpired() {
        Counter.builder("job.lease.expirations")
                .description("Running jobs whose lease expired and were taken back by the reaper")
                .register(registry)
                .increment();
    }

    public void jobsByState(Map<String, Long> counts) {
        jobsByState.forEach((state, value) -> value.set(counts.getOrDefault(state, 0L)));
    }
}
