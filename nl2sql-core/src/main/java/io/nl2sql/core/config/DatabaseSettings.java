package io.nl2sql.core.config;

import io.nl2sql.core.ConfigurationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How to reach the database. Either set {@link #type} plus host/port/name, or a full JDBC {@link #url}
 * (the URL wins). Use a read-only account: guardrails are the second line of defence.
 */
public class DatabaseSettings {
    /** mysql, mariadb, postgresql, sqlserver, oracle or sqlite. */
    private String type;
    private String host;
    private Integer port;
    /** Database name; Oracle: the service name; SQLite: the file path. */
    private String name;
    /** Optional schema holding the tables (PostgreSQL, SQL Server, Oracle). */
    private String schema;
    private String username;
    private String password;
    /** Extra driver settings: "key=value&amp;key2=value2" (';' also accepted). */
    private String options;
    /** Full JDBC URL; overrides type/host/port/name when set. */
    private String url;
    private List<String> includeTables = new ArrayList<>();
    private List<String> excludeTables = new ArrayList<>();
    /** Send up to 5 example values of low-cardinality text columns to the LLM. */
    private boolean sampleValues = true;
    private int poolSize = 5;

    /** The JDBC URL to connect with (without credentials). */
    public String jdbcUrl() {
        if (notBlank(url)) return url.trim();
        if (!notBlank(type)) {
            throw new ConfigurationException("Database not configured. Set nl2sql.db.type (NL2SQL_DB_TYPE): "
                    + "mysql, mariadb, postgresql, sqlserver, oracle or sqlite, with host, port and name - "
                    + "or a full JDBC URL in nl2sql.db.url (NL2SQL_DB_URL).");
        }
        DbType t = DbType.parse(type);
        Map<String, String> opts = parseOptions(options);
        if (t == DbType.SQLITE) {
            require(name, "SQLite needs the database file path in nl2sql.db.name (NL2SQL_DB_NAME)");
            return "jdbc:sqlite:" + name.trim();
        }
        require(host, t.id() + ": the server host name is required (nl2sql.db.host / NL2SQL_DB_HOST)");
        String h = host.trim();
        boolean namedInstance = t == DbType.SQLSERVER && h.contains("\\");
        Integer p = port != null ? port : (namedInstance ? null : t.defaultPort());
        String hostPort = p == null ? h : h + ":" + p;
        return switch (t) {
            case ORACLE -> {
                require(name, "Oracle: the service name is required in nl2sql.db.name (e.g. ORCLPDB1)");
                yield "jdbc:oracle:thin:@//" + hostPort + "/" + name.trim() + query(opts, "?", "&");
            }
            case SQLSERVER -> {
                require(name, "sqlserver: the database name is required (nl2sql.db.name / NL2SQL_DB_NAME)");
                Map<String, String> all = new LinkedHashMap<>();
                all.put("databaseName", name.trim());
                all.putAll(opts);
                yield "jdbc:sqlserver://" + hostPort + query(all, ";", ";");
            }
            default -> {
                require(name, t.id() + ": the database name is required (nl2sql.db.name / NL2SQL_DB_NAME)");
                String scheme = switch (t) {
                    case MYSQL -> "mysql";
                    case MARIADB -> "mariadb";
                    default -> "postgresql";
                };
                yield "jdbc:" + scheme + "://" + hostPort + "/" + name.trim() + query(opts, "?", "&");
            }
        };
    }

    public Dialect dialect() {
        if (notBlank(url)) return Dialect.fromJdbcUrl(url.trim());
        jdbcUrl(); // validates
        return DbType.parse(type).dialect();
    }

    static Map<String, String> parseOptions(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!notBlank(raw)) return out;
        for (String part : raw.replace(';', '&').split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return out;
    }

    private static String query(Map<String, String> opts, String first, String sep) {
        if (opts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(first);
        opts.forEach((k, v) -> {
            if (sb.length() > first.length()) sb.append(sep);
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    private static void require(String value, String message) {
        if (!notBlank(value)) throw new ConfigurationException(message);
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ---- getters / setters (bound from properties or environment variables) ----
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSchema() { return notBlank(schema) ? schema.trim() : null; }
    public void setSchema(String schema) { this.schema = schema; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public List<String> getIncludeTables() { return includeTables; }
    public void setIncludeTables(List<String> includeTables) { this.includeTables = clean(includeTables); }
    public List<String> getExcludeTables() { return excludeTables; }
    public void setExcludeTables(List<String> excludeTables) { this.excludeTables = clean(excludeTables); }
    public boolean isSampleValues() { return sampleValues; }
    public void setSampleValues(boolean sampleValues) { this.sampleValues = sampleValues; }
    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int poolSize) { this.poolSize = poolSize; }

    static List<String> clean(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in != null) for (String s : in) if (notBlank(s)) out.add(s.trim());
        return out;
    }

    @Override
    public String toString() { // never print the password
        return "DatabaseSettings[url=" + (notBlank(url) ? "<set>" : type + "://" + host + ":" + port + "/" + name)
                + ", schema=" + schema + ", username=" + username + ", password=" + (notBlank(password) ? "<set>" : "") + "]";
    }
}
