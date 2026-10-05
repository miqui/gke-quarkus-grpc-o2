package dev.miqui.jobmanager.repo;

import dev.miqui.jobmanager.v1.JobType;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** The job_types registry (read-only for the API). */
@ApplicationScoped
public class JobTypeRepository {

    public List<JobType> all(Connection c) throws SQLException {
        return Db.list(c, """
                        SELECT name, description, default_max_attempts, default_lease_seconds
                        FROM job_types ORDER BY name
                        """,
                Rows::type);
    }
}
