package dev.miqui.jobmanager.repo;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Plain JDBC on the Agroal pool, no ORM. Transactions are explicit ({@link #tx}) rather than
 * declarative, so it is visible which statements commit together and that cache evictions happen
 * after the commit.
 *
 * <p>Every statement is a sample of the {@code jdbc.query} timer (the name the Spring Boot
 * service's datasource-micrometer used, so the "DB Query Time" panel carries over); the span per
 * statement comes from Quarkus' JDBC telemetry.
 */
@ApplicationScoped
public class Db {

    private final AgroalDataSource dataSource;

    public Db(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** Runs {@code work} in one transaction: committed if it returns, rolled back if it throws. */
    public <T> T tx(Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException(e);
        }
    }

    public static <T> List<T> list(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        Timer.Sample sample = Timer.start(Metrics.globalRegistry);
        try (PreparedStatement statement = prepare(c, sql, params); ResultSet rs = statement.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapper.map(rs));
            }
            return rows;
        } finally {
            sample.stop(timer("query"));
        }
    }

    public static <T> Optional<T> one(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        List<T> rows = list(c, sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public static long count(Connection c, String sql, Object... params) throws SQLException {
        return one(c, sql, rs -> rs.getLong(1), params).orElseThrow();
    }

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        Timer.Sample sample = Timer.start(Metrics.globalRegistry);
        try (PreparedStatement statement = prepare(c, sql, params)) {
            return statement.executeUpdate();
        } finally {
            sample.stop(timer("update"));
        }
    }

    private static Timer timer(String operation) {
        return Timer.builder("jdbc.query")
                .description("JDBC statements, including reading the result set")
                .tag("jdbc_operation", operation)
                .serviceLevelObjectives(ms(1), ms(2), ms(5), ms(10), ms(25), ms(50), ms(100), ms(250), ms(1000))
                .register(Metrics.globalRegistry);
    }

    private static Duration ms(long millis) {
        return Duration.ofMillis(millis);
    }

    /** A text[] parameter (e.g. for {@code = ANY(?)}). */
    public record TextArray(List<String> values) {
        public TextArray {
            values = List.copyOf(values);
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement statement = c.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) {
                bind(c, statement, i + 1, params[i]);
            }
            return statement;
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
    }

    private static void bind(Connection c, PreparedStatement statement, int index, Object value) throws SQLException {
        switch (value) {
            // Typed nulls: an untyped one makes casts like ?::int ambiguous for the planner.
            case null -> statement.setNull(index, Types.VARCHAR);
            case Instant instant -> statement.setTimestamp(index, Timestamp.from(instant));
            case TextArray array -> statement.setArray(index, c.createArrayOf("text", array.values().toArray()));
            default -> statement.setObject(index, value);
        }
    }
}
