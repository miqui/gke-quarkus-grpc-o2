package dev.miqui.jobmanager.repo;

import java.sql.SQLException;

/** An unchecked SQLException, keeping the SQLSTATE for callers that map specific ones. */
public class DataAccessException extends RuntimeException {

    public static final String UNIQUE_VIOLATION = "23505";
    public static final String FK_VIOLATION = "23503";

    private final String sqlState;

    public DataAccessException(SQLException cause) {
        super(cause.getMessage(), cause);
        this.sqlState = cause.getSQLState();
    }

    public boolean is(String state) {
        return state.equals(sqlState);
    }
}
