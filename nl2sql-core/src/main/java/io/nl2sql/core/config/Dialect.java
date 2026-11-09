package io.nl2sql.core.config;

/** SQL dialects nl2sql generates and validates. */
public enum Dialect {
    MYSQL("MySQL"),
    MARIADB("MariaDB"),
    POSTGRES("PostgreSQL"),
    SQLSERVER("Microsoft SQL Server (T-SQL)"),
    ORACLE("Oracle"),
    SQLITE("SQLite");

    private final String displayName;

    Dialect(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Short product label for UIs ("SQL Server", not the T-SQL wording used in prompts). */
    public String productName() {
        return switch (this) {
            case SQLSERVER -> "SQL Server";
            default -> displayName;
        };
    }

    /** Whether a schema is separate from the database (so table names can be schema-qualified). */
    public boolean hasSchemas() {
        return this == POSTGRES || this == SQLSERVER || this == ORACLE;
    }

    /** Quote an identifier exactly as stored, so any name is safe in generated helper queries. */
    public String quote(String identifier) {
        return switch (this) {
            case MYSQL, MARIADB -> "`" + identifier.replace("`", "``") + "`";
            case SQLSERVER -> "[" + identifier.replace("]", "]]") + "]";
            default -> "\"" + identifier.replace("\"", "\"\"") + "\"";
        };
    }

    /** Detect the dialect from a JDBC URL. */
    public static Dialect fromJdbcUrl(String url) {
        String u = url == null ? "" : url.toLowerCase();
        if (u.startsWith("jdbc:mysql:")) return MYSQL;
        if (u.startsWith("jdbc:mariadb:")) return MARIADB;
        if (u.startsWith("jdbc:postgresql:")) return POSTGRES;
        if (u.startsWith("jdbc:sqlserver:")) return SQLSERVER;
        if (u.startsWith("jdbc:oracle:")) return ORACLE;
        if (u.startsWith("jdbc:sqlite:")) return SQLITE;
        throw new io.nl2sql.core.ConfigurationException(
                "Cannot tell the database type from the JDBC URL '" + url + "'. Set nl2sql.db.type instead.");
    }
}
