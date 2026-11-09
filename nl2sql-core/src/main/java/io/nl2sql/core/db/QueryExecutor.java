package io.nl2sql.core.db;

import io.nl2sql.core.QueryExecutionException;
import io.nl2sql.core.config.Dialect;

import javax.sql.DataSource;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Runs validated SQL inside a transaction that is always rolled back, read-only and time-limited where the
 * database supports it. A read-only database account is still required: this is the second line of defence.
 */
public class QueryExecutor {
    private final DataSource dataSource;
    private final Dialect dialect;
    private final int timeoutSeconds;
    private final int maxRows;

    public QueryExecutor(DataSource dataSource, Dialect dialect, int timeoutSeconds, int maxRows) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRows = maxRows;
    }

    public ExecutionResult run(String sql) {
        long start = System.nanoTime();
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            boolean manualTx = dialect == Dialect.MYSQL || dialect == Dialect.MARIADB;
            try {
                conn.setAutoCommit(manualTx); // MySQL/MariaDB: we issue START TRANSACTION ourselves
                harden(conn);
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(timeoutSeconds);
                    st.setMaxRows(maxRows + 1);
                    st.setFetchSize(Math.min(500, maxRows + 1));
                    try (ResultSet rs = st.executeQuery(sql)) {
                        ResultSetMetaData md = rs.getMetaData();
                        List<String> columns = new ArrayList<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
                        List<List<Object>> rows = new ArrayList<>();
                        while (rs.next() && rows.size() <= maxRows) {
                            List<Object> row = new ArrayList<>(columns.size());
                            for (int i = 1; i <= columns.size(); i++) row.add(toJsonValue(rs.getObject(i)));
                            rows.add(row);
                        }
                        boolean truncated = rows.size() > maxRows;
                        if (truncated) rows = rows.subList(0, maxRows);
                        return new ExecutionResult(columns, rows, truncated, (System.nanoTime() - start) / 1e6);
                    }
                }
            } finally {
                undo(conn);
                try {
                    conn.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                    // the pool resets it anyway
                }
            }
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            int nl = msg.indexOf('\n');
            throw new QueryExecutionException(e.getClass().getSimpleName() + ": "
                    + (nl < 0 ? msg : msg.substring(0, nl)).strip(), sql, e);
        }
    }

    /** Session-level protection for each database. */
    private void harden(Connection conn) throws SQLException {
        long ms = timeoutSeconds * 1000L;
        try (Statement st = conn.createStatement()) {
            switch (dialect) {
                case MYSQL -> {
                    st.execute("SET SESSION max_execution_time = " + ms); // SELECT statements only
                    st.execute("START TRANSACTION READ ONLY");
                }
                case MARIADB -> {
                    st.execute("SET SESSION max_statement_time = " + timeoutSeconds);
                    st.execute("START TRANSACTION READ ONLY");
                }
                case POSTGRES -> {
                    st.execute("SET TRANSACTION READ ONLY"); // must come first in the transaction
                    st.execute("SET LOCAL statement_timeout = " + ms);
                }
                case ORACLE -> st.execute("SET TRANSACTION READ ONLY"); // must come first in the transaction
                case SQLSERVER -> st.execute("SET LOCK_TIMEOUT " + ms); // no read-only mode: use a db_datareader login
                case SQLITE -> st.execute("PRAGMA query_only = ON");
            }
        }
    }

    private void undo(Connection conn) {
        try (Statement st = conn.createStatement()) {
            if (dialect == Dialect.MYSQL || dialect == Dialect.MARIADB) st.execute("ROLLBACK");
            else conn.rollback();
            if (dialect == Dialect.SQLITE) st.execute("PRAGMA query_only = OFF");
        } catch (SQLException ignored) {
            // the connection is returned to the pool; nothing was committed
        }
    }

    /** Values that serialise cleanly to JSON. */
    static Object toJsonValue(Object v) throws SQLException {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Short || v instanceof Double || v instanceof Float || v instanceof BigDecimal
                || v instanceof BigInteger) {
            return v;
        }
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof Timestamp ts) return ts.toLocalDateTime().toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof java.sql.Time t) return t.toLocalTime().toString();
        if (v instanceof TemporalAccessor) return v.toString();
        if (v instanceof byte[] b) return HexFormat.of().formatHex(b, 0, Math.min(b.length, 256));
        if (v instanceof Clob c) {
            try (Reader r = c.getCharacterStream()) {
                char[] buf = new char[4000];
                int n = r.read(buf);
                return n <= 0 ? "" : new String(buf, 0, n);
            } catch (java.io.IOException e) {
                return "";
            }
        }
        String s = v.toString();
        // e.g. oracle.sql.TIMESTAMPTZ or PGobject: fall back to text
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    @Override
    public String toString() {
        return "QueryExecutor[" + dialect.name().toLowerCase(Locale.ROOT) + ", timeout=" + timeoutSeconds + "s, maxRows=" + maxRows + "]";
    }
}
