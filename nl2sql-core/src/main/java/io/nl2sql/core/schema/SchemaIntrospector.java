package io.nl2sql.core.schema;

import io.nl2sql.core.SchemaException;
import io.nl2sql.core.config.Dialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads tables, columns, keys and comments through JDBC metadata, then samples a few values of
 * low-cardinality text columns so the LLM matches codes like 'D' or 'DELIVERED' exactly.
 * Personal-looking columns and values are never sampled.
 */
public class SchemaIntrospector {
    private static final Logger log = LoggerFactory.getLogger(SchemaIntrospector.class);

    private static final int SAMPLE_COUNT = 5;
    private static final int SAMPLE_DISTINCT_LIMIT = 50;
    private static final int SAMPLE_SCAN_ROWS = 5000;
    private static final String[] TEXT_TYPES = {"char", "text", "string", "enum", "varchar", "nvarchar", "nchar"};

    private static final Set<String> PII_WORDS = Set.of(
            "name", "firstname", "lastname", "fullname", "surname", "email", "mail", "phone", "mobile",
            "telephone", "tel", "fax", "address", "addr", "street", "zip", "zipcode", "postcode", "pincode",
            "dob", "birth", "birthdate", "ssn", "pan", "aadhaar", "aadhar", "passport", "license", "licence",
            "iban", "account", "card", "password", "pwd", "secret", "token", "ip", "gender", "salary");
    private static final Pattern PII_VALUE = Pattern.compile(
            "@|\\+?\\d[\\d\\s().-]{7,}\\d|^[A-Z]{5}\\d{4}[A-Z]$|\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b|^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    private final DataSource dataSource;
    private final Dialect dialect;

    public SchemaIntrospector(DataSource dataSource, Dialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    public SchemaCatalog introspect(String schema, List<String> include, List<String> exclude,
                                    List<String> blockedColumns, boolean sampleValues) {
        Set<String> inc = lower(include);
        Set<String> exc = lower(exclude);
        Set<String> blocked = lower(blockedColumns);
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String catalog = catalogFor(conn);
            String schemaPattern = schemaFor(conn, schema);
            SchemaCatalog cat = new SchemaCatalog(dialect, schema);

            List<String[]> tableRows = new ArrayList<>(); // physical name, remarks
            try (ResultSet rs = md.getTables(catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String physical = rs.getString("TABLE_NAME");
                    if (dialect == Dialect.SQLITE && physical.startsWith("sqlite_")) continue;
                    tableRows.add(new String[]{physical, rs.getString("REMARKS")});
                }
            }
            tableRows.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));

            Map<String, String> displayByPhysical = new LinkedHashMap<>();
            for (String[] row : tableRows) displayByPhysical.put(row[0], display(row[0]));

            for (String[] row : tableRows) {
                String physical = row[0];
                String name = displayByPhysical.get(physical);
                String lname = name.toLowerCase(Locale.ROOT);
                if ((!inc.isEmpty() && !inc.contains(lname)) || exc.contains(lname)) continue;

                Set<String> pk = new HashSet<>();
                try (ResultSet rs = md.getPrimaryKeys(catalog, schemaPattern, physical)) {
                    while (rs.next()) pk.add(rs.getString("COLUMN_NAME"));
                }
                List<Column> columns = new ArrayList<>();
                try (ResultSet rs = md.getColumns(catalog, schemaPattern, escape(md, physical), "%")) {
                    while (rs.next()) {
                        if (!physical.equals(rs.getString("TABLE_NAME"))) continue; // '_' is a wildcard
                        String colPhysical = rs.getString("COLUMN_NAME");
                        String colName = display(colPhysical);
                        if (blocked.contains((name + "." + colName).toLowerCase(Locale.ROOT))) continue;
                        columns.add(new Column(colName, colPhysical, typeOf(rs),
                                rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                pk.contains(colPhysical), rs.getString("REMARKS")));
                    }
                }
                List<ForeignKey> fks = foreignKeys(md, catalog, schemaPattern, physical, displayByPhysical);
                cat.add(new Table(name, physical, columns, fks, row[1]));
            }
            if (sampleValues) collectSamples(conn, cat, schemaPattern);
            return cat;
        } catch (SQLException e) {
            throw new SchemaException("Schema introspection failed: " + firstLine(e.getMessage()) + rootCause(e)
                    + (schema != null ? " (check that the schema '" + schema + "' exists and the user can read it)" : ""), e);
        }
    }

    private String catalogFor(Connection conn) throws SQLException {
        return switch (dialect) {
            case MYSQL, MARIADB, SQLSERVER -> conn.getCatalog();
            default -> null;
        };
    }

    private String schemaFor(Connection conn, String schema) throws SQLException {
        return switch (dialect) {
            case POSTGRES, SQLSERVER -> schema != null ? schema : conn.getSchema();
            case ORACLE -> schema != null ? (isUnquotedOracle(schema) ? schema.toUpperCase(Locale.ROOT) : schema)
                    : conn.getMetaData().getUserName();
            default -> null; // MySQL/MariaDB: schema = database (catalog); SQLite: none
        };
    }

    private static boolean isUnquotedOracle(String s) {
        return s.matches("[A-Za-z][A-Za-z0-9_$#]*");
    }

    /** Oracle stores unquoted names in upper case; show them in lower case (still valid unquoted SQL). */
    private String display(String physical) {
        if (dialect == Dialect.ORACLE && physical.equals(physical.toUpperCase(Locale.ROOT))) {
            return physical.toLowerCase(Locale.ROOT);
        }
        return physical;
    }

    private static String escape(DatabaseMetaData md, String name) throws SQLException {
        String esc = md.getSearchStringEscape();
        if (esc == null || esc.isEmpty()) return name;
        return name.replace(esc, esc + esc).replace("_", esc + "_").replace("%", esc + "%");
    }

    private static String typeOf(ResultSet rs) throws SQLException {
        String type = rs.getString("TYPE_NAME");
        int size = rs.getInt("COLUMN_SIZE");
        String lt = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (size > 0 && (lt.contains("char") || lt.equals("decimal") || lt.equals("numeric") || lt.equals("number"))
                && size < 100000) {
            int scale = rs.getInt("DECIMAL_DIGITS");
            return type + "(" + size + (lt.contains("char") || rs.wasNull() || scale <= 0 ? "" : "," + scale) + ")";
        }
        return type;
    }

    private List<ForeignKey> foreignKeys(DatabaseMetaData md, String catalog, String schema, String physical,
                                         Map<String, String> displayByPhysical) {
        Map<String, List<String[]>> byName = new TreeMap<>();
        try (ResultSet rs = md.getImportedKeys(catalog, schema, physical)) {
            int anon = 0;
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                if (fkName == null) fkName = "fk" + rs.getString("PKTABLE_NAME") + (rs.getShort("KEY_SEQ") == 1 ? ++anon : anon);
                byName.computeIfAbsent(fkName, k -> new ArrayList<>()).add(new String[]{
                        rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME"),
                        String.valueOf(rs.getShort("KEY_SEQ"))});
            }
        } catch (SQLException e) {
            log.debug("Could not read foreign keys of {}: {}", physical, e.getMessage());
            return List.of();
        }
        List<ForeignKey> out = new ArrayList<>();
        for (List<String[]> parts : byName.values()) {
            parts.sort((a, b) -> Integer.compare(Integer.parseInt(a[3]), Integer.parseInt(b[3])));
            List<String> cols = new ArrayList<>();
            List<String> refCols = new ArrayList<>();
            for (String[] p : parts) {
                cols.add(display(p[0]));
                refCols.add(display(p[2]));
            }
            String refTable = displayByPhysical.getOrDefault(parts.get(0)[1], display(parts.get(0)[1]));
            out.add(new ForeignKey(cols, refTable, refCols));
        }
        return out;
    }

    // ---------------------------------------------------------------- sample values

    private void collectSamples(Connection conn, SchemaCatalog cat, String schemaPattern) {
        for (Table t : cat.tables().values()) {
            for (Column c : t.columns()) {
                if (c.primaryKey() || !isText(c.type()) || isPiiColumn(c.name())) continue;
                String sql = sampleSql(qualified(t.physicalName(), schemaPattern), dialect.quote(c.physicalName()));
                List<String> values = new ArrayList<>();
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(10);
                    try (ResultSet rs = st.executeQuery(sql)) {
                        while (rs.next()) {
                            String v = rs.getString(1);
                            if (v != null) values.add(v.length() > 60 ? v.substring(0, 60) : v);
                        }
                    }
                } catch (SQLException e) {
                    log.debug("Skipping samples for {}.{}: {}", t.name(), c.name(), e.getMessage());
                    resetAfterError(conn);
                    continue;
                }
                if (values.isEmpty() || values.size() > SAMPLE_DISTINCT_LIMIT) continue;
                if (values.stream().anyMatch(SchemaIntrospector::looksPersonal)) continue;
                c.sampleValues(values.subList(0, Math.min(SAMPLE_COUNT, values.size())));
            }
        }
    }

    private void resetAfterError(Connection conn) {
        try {
            if (!conn.getAutoCommit()) conn.rollback(); // PostgreSQL aborts the transaction after an error
        } catch (SQLException ignored) {
            // best effort
        }
    }

    private String qualified(String physicalTable, String schema) {
        String t = dialect.quote(physicalTable);
        return schema != null && dialect.hasSchemas() ? dialect.quote(schema) + "." + t : t;
    }

    /** Distinct values from a bounded slice of the table, so huge tables stay cheap. */
    String sampleSql(String table, String col) {
        int probe = SAMPLE_SCAN_ROWS;
        int keep = SAMPLE_DISTINCT_LIMIT + 1;
        return switch (dialect) {
            case SQLSERVER -> "SELECT DISTINCT TOP " + keep + " v FROM (SELECT TOP " + probe + " " + col + " AS v FROM "
                    + table + " WHERE " + col + " IS NOT NULL) s";
            case ORACLE -> "SELECT DISTINCT v FROM (SELECT " + col + " AS v FROM " + table + " WHERE " + col
                    + " IS NOT NULL FETCH FIRST " + probe + " ROWS ONLY) s FETCH FIRST " + keep + " ROWS ONLY";
            default -> "SELECT DISTINCT v FROM (SELECT " + col + " AS v FROM " + table + " WHERE " + col
                    + " IS NOT NULL LIMIT " + probe + ") s LIMIT " + keep;
        };
    }

    static boolean isText(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.contains("clob") || t.contains("blob") || t.contains("binary")) return false;
        for (String k : TEXT_TYPES) if (t.contains(k)) return true;
        return false;
    }

    public static boolean isPiiColumn(String name) {
        String spaced = CAMEL.matcher(name).replaceAll("_").toLowerCase(Locale.ROOT);
        for (String w : spaced.split("[^a-z0-9]+")) {
            if (!w.isEmpty() && PII_WORDS.contains(w)) return true;
        }
        return PII_WORDS.contains(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
    }

    public static boolean looksPersonal(String value) {
        return PII_VALUE.matcher(value.strip()).find();
    }

    private static Set<String> lower(List<String> in) {
        Set<String> out = new HashSet<>();
        if (in != null) for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** Pool timeouts hide the real reason (wrong password, host down...): show the innermost cause too. */
    static String rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        if (root == e || root.getMessage() == null) return "";
        return ". Cause: " + firstLine(root.getMessage());
    }
}
