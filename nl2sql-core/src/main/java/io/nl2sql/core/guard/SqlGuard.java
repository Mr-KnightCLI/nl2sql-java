package io.nl2sql.core.guard;

import io.nl2sql.core.GuardrailViolation;
import io.nl2sql.core.config.Dialect;
import io.nl2sql.core.config.GuardrailSettings;
import io.nl2sql.core.schema.Column;
import io.nl2sql.core.schema.SchemaCatalog;
import io.nl2sql.core.schema.Table;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static validation of LLM-generated SQL. Never trust model output; every query must pass:
 * <ol>
 *   <li>exactly one statement that parses;</li>
 *   <li>a read-only query (SELECT, UNION, CTE) with no SELECT INTO, locking clauses or file access;</li>
 *   <li>only exposed tables and real columns;</li>
 *   <li>no blocked (e.g. personal) columns and no dangerous functions.</li>
 * </ol>
 * The row limit is enforced when the query runs ({@code Statement.setMaxRows}), in every database's own way.
 * These checks complement - never replace - a read-only database account.
 */
public class SqlGuard {
    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "(?i)\\b(INTO|OUTFILE|DUMPFILE|WAITFOR|OPENROWSET|OPENQUERY|OPENDATASOURCE)\\b"
                    + "|\\bFOR\\s+(NO\\s+KEY\\s+)?UPDATE\\b|\\bFOR\\s+(KEY\\s+)?SHARE\\b|\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b");
    private static final Pattern COUNT_STAR = Pattern.compile("(?i)\\bCOUNT\\s*\\(\\s*\\*\\s*\\)");
    /** Words some dialects use as bare identifiers inside functions, e.g. DATEADD(day, ...), or pseudo-columns. */
    private static final Set<String> PSEUDO_COLUMNS = Set.of(
            "year", "yy", "yyyy", "quarter", "qq", "q", "month", "mm", "m", "week", "wk", "ww", "isowk", "iso_week",
            "day", "days", "dd", "d", "dayofyear", "dy", "y", "weekday", "dw", "dow", "doy", "isodow", "hour", "hh",
            "minute", "mi", "n", "second", "ss", "s", "millisecond", "ms", "microsecond", "mcs", "nanosecond", "ns",
            "epoch", "sysdate", "systimestamp", "rownum", "level", "user", "current_user", "session_user",
            "true", "false", "null", "unknown", "current_date", "current_time", "current_timestamp", "localtimestamp");

    private final SchemaCatalog catalog;
    private final GuardrailSettings settings;
    private final Set<String> blocked = new HashSet<>();
    private final Set<String> forbiddenFunctions = new HashSet<>();

    public SqlGuard(SchemaCatalog catalog, GuardrailSettings settings) {
        this.catalog = catalog;
        this.settings = settings;
        for (String b : settings.getBlockedColumns()) blocked.add(b.toLowerCase(Locale.ROOT));
        for (String f : settings.getForbiddenFunctions()) forbiddenFunctions.add(f.toLowerCase(Locale.ROOT));
    }

    public ValidatedSql validate(String rawSql) {
        String sql = rawSql == null ? "" : rawSql.strip();
        while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).strip();
        if (sql.isEmpty()) throw new GuardrailViolation("Empty SQL", rawSql);

        String stripped = SqlLexer.stripLiteralsAndComments(sql);
        String keywords = SqlLexer.keywordsOnly(stripped);
        if (keywords.contains(";")) throw new GuardrailViolation("Exactly one SQL statement is allowed", sql);
        var forbidden = FORBIDDEN_KEYWORDS.matcher(keywords);
        if (forbidden.find()) {
            throw new GuardrailViolation("Forbidden operation in query: " + forbidden.group().toUpperCase(Locale.ROOT)
                    .replaceAll("\\s+", " "), sql);
        }
        for (String fn : SqlLexer.functionNames(keywords)) checkFunction(fn, sql);

        Statement stmt = parse(sql);
        if (!(stmt instanceof Select)) {
            throw new GuardrailViolation("Only read-only SELECT queries are allowed (got "
                    + stmt.getClass().getSimpleName().toUpperCase(Locale.ROOT) + ")", sql);
        }

        Collector collector = new Collector();
        List<String> realNames = collector.getTableList(stmt);
        Set<String> cteNames = SqlLexer.cteNames(stripped);
        Set<String> defined = SqlLexer.definedNames(stripped);

        // ---- tables
        Map<String, Table> aliasMap = new LinkedHashMap<>();
        Set<String> derivedAliases = new HashSet<>();
        List<String> used = new ArrayList<>();
        List<net.sf.jsqlparser.schema.Table> toQualify = new ArrayList<>();
        Set<String> realLower = new HashSet<>();
        for (String r : realNames) realLower.add(r.toLowerCase(Locale.ROOT));
        for (net.sf.jsqlparser.schema.Table t : collector.tables) {
            String name = SqlLexer.unquote(t.getName());
            if (name == null) continue;
            if (cteNames.contains(name.toLowerCase(Locale.ROOT))) {
                // "FROM big b" where big is a CTE: b names a derived table, its columns are checked inside the CTE
                if (t.getAlias() != null && t.getAlias().getName() != null) {
                    derivedAliases.add(SqlLexer.unquote(t.getAlias().getName()).toLowerCase(Locale.ROOT));
                }
                continue;
            }
            if (!realLower.contains(t.getFullyQualifiedName().toLowerCase(Locale.ROOT))) continue;
            if (t.getDatabase() != null && t.getDatabase().getDatabaseName() != null
                    && !t.getDatabase().getDatabaseName().isBlank()) {
                throw new GuardrailViolation("Cross-database reference not allowed: " + t.getFullyQualifiedName(), sql);
            }
            String schemaPart = SqlLexer.unquote(t.getSchemaName());
            if (schemaPart != null && !schemaPart.isBlank() && catalog.schema() != null
                    && !schemaPart.equalsIgnoreCase(catalog.schema())) {
                throw new GuardrailViolation("Schema '" + schemaPart + "' is not exposed", sql);
            }
            Table real = catalog.table(name);
            if (real == null) throw new GuardrailViolation("Unknown or non-exposed table: " + name, sql);
            if (!used.contains(real.name())) used.add(real.name());
            aliasMap.put(real.name().toLowerCase(Locale.ROOT), real);
            if (t.getAlias() != null && t.getAlias().getName() != null) {
                aliasMap.put(SqlLexer.unquote(t.getAlias().getName()).toLowerCase(Locale.ROOT), real);
            }
            if (schemaPart == null || schemaPart.isBlank()) toQualify.add(t);
        }
        if (used.isEmpty() && cteNames.isEmpty()) {
            throw new GuardrailViolation("Query must read from at least one exposed table", sql);
        }

        // ---- columns
        Set<String> realColumns = new HashSet<>();
        for (String u : used) for (Column c : catalog.table(u).columns()) realColumns.add(c.name().toLowerCase(Locale.ROOT));
        for (net.sf.jsqlparser.schema.Column col : collector.columns) {
            String cname = SqlLexer.unquote(col.getColumnName());
            if (cname == null || cname.isBlank()) continue;
            String lc = cname.toLowerCase(Locale.ROOT);
            String qualifier = col.getTable() == null ? null : SqlLexer.unquote(col.getTable().getName());
            if (qualifier != null && !qualifier.isBlank()) {
                String lq = qualifier.toLowerCase(Locale.ROOT);
                Table real = aliasMap.get(lq);
                if (real == null) {
                    if (cteNames.contains(lq) || derivedAliases.contains(lq) || defined.contains(lq)) continue; // CTE / derived table column
                    throw new GuardrailViolation("Unknown table alias '" + qualifier + "' in " + col, sql);
                }
                if (blocked.contains((real.name() + "." + cname).toLowerCase(Locale.ROOT))) {
                    throw new GuardrailViolation("Column " + real.name() + "." + cname + " is restricted", sql);
                }
                if (real.column(cname) == null) {
                    throw new GuardrailViolation("Unknown column " + real.name() + "." + cname, sql);
                }
            } else {
                for (String u : used) {
                    if (blocked.contains((u + "." + cname).toLowerCase(Locale.ROOT))) {
                        throw new GuardrailViolation("Column " + cname + " is restricted", sql);
                    }
                }
                if (!realColumns.contains(lc) && !defined.contains(lc) && !PSEUDO_COLUMNS.contains(lc)
                        && !cteNames.contains(lc)) {
                    throw new GuardrailViolation("Unknown column '" + cname + "'", sql);
                }
            }
        }

        // ---- SELECT * (would expose restricted columns)
        String noCount = COUNT_STAR.matcher(keywords).replaceAll(" ");
        if (noCount.contains("*")) {
            if (!settings.isAllowSelectStar()) {
                throw new GuardrailViolation("SELECT * is disabled; list the columns explicitly", sql);
            }
            for (String u : used) {
                String prefix = u.toLowerCase(Locale.ROOT) + ".";
                if (blocked.stream().anyMatch(b -> b.startsWith(prefix))) {
                    throw new GuardrailViolation("SELECT * is not allowed on " + u + " because it has restricted columns", sql);
                }
            }
        }

        // ---- functions found by the parser (covers names the scanner may miss)
        for (Function f : collector.functions) {
            if (f.getName() != null) checkFunction(f.getName().toLowerCase(Locale.ROOT), sql);
        }

        // ---- qualify tables with the configured schema (PostgreSQL, SQL Server, Oracle)
        String finalSql = sql;
        Dialect d = catalog.dialect();
        if (catalog.schema() != null && d.hasSchemas() && !toQualify.isEmpty()) {
            for (net.sf.jsqlparser.schema.Table t : toQualify) t.setSchemaName(catalog.schema());
            finalSql = stmt.toString();
        }
        return new ValidatedSql(finalSql, used);
    }

    private void checkFunction(String fullName, String sql) {
        String last = fullName.contains(".") ? fullName.substring(fullName.lastIndexOf('.') + 1) : fullName;
        if (forbiddenFunctions.contains(fullName) || forbiddenFunctions.contains(last)) {
            throw new GuardrailViolation("Function " + last + "() is not allowed", sql);
        }
    }

    private Statement parse(String sql) {
        try {
            if (catalog.dialect() == Dialect.SQLSERVER) {
                return CCJSqlParserUtil.parse(sql, parser -> parser.withSquareBracketQuotation(true));
            }
            return CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            String msg = e.getCause() != null && e.getCause().getMessage() != null ? e.getCause().getMessage() : e.getMessage();
            throw new GuardrailViolation("SQL could not be parsed: " + firstLine(msg), sql);
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).strip();
    }

    /** Collects every table, column and function node while JSqlParser walks the statement. */
    private static final class Collector extends TablesNamesFinder {
        final List<net.sf.jsqlparser.schema.Table> tables = new ArrayList<>();
        final List<net.sf.jsqlparser.schema.Column> columns = new ArrayList<>();
        final List<Function> functions = new ArrayList<>();

        @Override
        public void visit(net.sf.jsqlparser.schema.Table table) {
            tables.add(table);
            super.visit(table);
        }

        @Override
        public void visit(net.sf.jsqlparser.schema.Column column) {
            columns.add(column); // not passed on: a qualifier like "c." is an alias, not a table
        }

        @Override
        public void visit(Function function) {
            functions.add(function);
            super.visit(function);
        }
    }
}
