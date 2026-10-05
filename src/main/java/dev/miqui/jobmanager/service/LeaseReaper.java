package dev.miqui.jobmanager.service;

import dev.miqui.jobmanager.repo.Db;
import dev.miqui.jobmanager.repo.JobRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Takes back the leases of workers that stopped heartbeating: the job goes back to QUEUED if it
 * has attempts left, otherwise to FAILED. Runs on every replica; SKIP LOCKED in
 * {@link JobRepository#expireLeases} keeps them from processing the same rows.
 */
@ApplicationScoped
public class LeaseReaper {

    static final int BATCH = 100;

    private static final Logger log = Logger.getLogger(LeaseReaper.class);

    private final Db db;
    private final JobRepository jobs;
    private final JobMetrics metrics;

    public LeaseReaper(Db db, JobRepository jobs, JobMetrics metrics) {
        this.db = db;
        this.jobs = jobs;
        this.metrics = metrics;
    }

    @Scheduled(identity = "lease-reaper", every = "${app.jobs.reaper-interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduled() {
        reap();
    }

    /** Returns how many leases were taken back. */
    public int reap() {
        int total = 0;
        List<JobRepository.Expired> expired;
        do {
            expired = db.tx(c -> jobs.expireLeases(c, BATCH));
            for (JobRepository.Expired job : expired) {
                metrics.leaseExpired();
                metrics.transition("RUNNING", job.toState());
                log.infof("lease expired: job %s (worker %s) -> %s", job.jobId(), job.worker(), job.toState());
            }
            total += expired.size();
        } while (expired.size() == BATCH);
        return total;
    }
}
