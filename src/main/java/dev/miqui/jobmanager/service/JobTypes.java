package dev.miqui.jobmanager.service;

import dev.miqui.jobmanager.repo.Db;
import dev.miqui.jobmanager.repo.JobTypeRepository;
import dev.miqui.jobmanager.v1.JobType;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The job type registry, read on every CreateJob and ClaimJob. It changes almost never (rows are
 * added with SQL), so it is cached per pod for a minute (quarkus.cache.caffeine."job-types").
 */
@ApplicationScoped
public class JobTypes {

    private final Db db;
    private final JobTypeRepository types;

    public JobTypes(Db db, JobTypeRepository types) {
        this.db = db;
        this.types = types;
    }

    /** By name, in name order. */
    @CacheResult(cacheName = "job-types")
    public Map<String, JobType> all() {
        Map<String, JobType> byName = new LinkedHashMap<>();
        db.tx(types::all).forEach(type -> byName.put(type.getName(), type));
        return byName;
    }
}
