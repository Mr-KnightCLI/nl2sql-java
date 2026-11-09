package io.nl2sql.core.config;

import io.nl2sql.core.ConfigurationException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Database products accepted in {@code nl2sql.db.type} / {@code NL2SQL_DB_TYPE}. */
public enum DbType {
    MYSQL("mysql", Dialect.MYSQL, 3306),
    MARIADB("mariadb", Dialect.MARIADB, 3306),
    POSTGRESQL("postgresql", Dialect.POSTGRES, 5432),
    SQLSERVER("sqlserver", Dialect.SQLSERVER, 1433),
    ORACLE("oracle", Dialect.ORACLE, 1521),
    SQLITE("sqlite", Dialect.SQLITE, 0);

    private static final Map<String, DbType> ALIASES = Map.ofEntries(
            Map.entry("postgres", POSTGRESQL), Map.entry("pg", POSTGRESQL), Map.entry("pgsql", POSTGRESQL),
            Map.entry("mssql", SQLSERVER), Map.entry("sql server", SQLSERVER), Map.entry("sql_server", SQLSERVER),
            Map.entry("tsql", SQLSERVER), Map.entry("oracledb", ORACLE));

    private final String id;
    private final Dialect dialect;
    private final int defaultPort;

    DbType(String id, Dialect dialect, int defaultPort) {
        this.id = id;
        this.dialect = dialect;
        this.defaultPort = defaultPort;
    }

    public String id() {
        return id;
    }

    public Dialect dialect() {
        return dialect;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public static DbType parse(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (DbType t : values()) {
            if (t.id.equals(v)) return t;
        }
        DbType alias = ALIASES.get(v);
        if (alias != null) return alias;
        throw new ConfigurationException("Unknown database type '" + value + "'. Use one of: "
                + Arrays.stream(values()).map(DbType::id).collect(Collectors.joining(", ")));
    }
}
