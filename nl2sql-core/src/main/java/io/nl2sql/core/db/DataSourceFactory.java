package io.nl2sql.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.nl2sql.core.config.DatabaseSettings;
import io.nl2sql.core.config.Dialect;

/** Builds the connection pool. It connects lazily, so the service starts even while the database is down. */
public final class DataSourceFactory {
    private DataSourceFactory() {
    }

    public static HikariDataSource create(DatabaseSettings db, int queryTimeoutSeconds) {
        String url = db.jdbcUrl();
        Dialect dialect = db.dialect();
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("nl2sql");
        cfg.setJdbcUrl(url);
        if (db.getUsername() != null && !db.getUsername().isBlank()) cfg.setUsername(db.getUsername());
        if (db.getPassword() != null && !db.getPassword().isEmpty()) cfg.setPassword(db.getPassword());
        cfg.setMaximumPoolSize(Math.max(1, db.getPoolSize()));
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(15_000);
        cfg.setInitializationFailTimeout(-1);
        switch (dialect) {
            case MYSQL -> {
                cfg.addDataSourceProperty("connectTimeout", "15000");
                cfg.addDataSourceProperty("useCursorFetch", "false");
            }
            case MARIADB -> cfg.addDataSourceProperty("connectTimeout", "15000");
            case POSTGRES -> {
                cfg.addDataSourceProperty("connectTimeout", "15");
                cfg.addDataSourceProperty("ApplicationName", "nl2sql");
            }
            case SQLSERVER -> {
                cfg.addDataSourceProperty("loginTimeout", "15");
                cfg.addDataSourceProperty("applicationName", "nl2sql");
            }
            case ORACLE -> cfg.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", "15000");
            case SQLITE -> {
                cfg.addDataSourceProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
                cfg.setMaximumPoolSize(1);
            }
        }
        return new HikariDataSource(cfg);
    }
}
