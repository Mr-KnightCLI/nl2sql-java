package io.nl2sql.core.guard;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, dialect-agnostic SQL scanner used alongside the parser: it removes comments and string literals so
 * keyword and structure checks cannot be fooled by text inside strings.
 */
final class SqlLexer {
    private static final String IDENT = "([A-Za-z_][\\w$#]*|\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\])";
    private static final Pattern CTE = Pattern.compile(
            "(?i)(?:\\bWITH\\s+(?:RECURSIVE\\s+)?|\\)\\s*,\\s*)" + IDENT + "\\s*(\\([^()]*\\))?\\s*AS\\s*(?:NOT\\s+)?(?:MATERIALIZED\\s+)?\\(");
    private static final Pattern AS_ALIAS = Pattern.compile("(?i)\\bAS\\s+" + IDENT);
    private static final Set<String> NOT_ALIAS = Set.of("as", "on", "where", "group", "order", "union", "intersect",
            "except", "minus", "join", "inner", "left", "right", "full", "cross", "outer", "limit", "having", "fetch",
            "offset", "and", "or", "then", "else", "end", "when", "desc", "asc", "from", "is", "not", "in", "over",
            "between", "like", "nulls", "using", "window", "qualify", "for", "select", "natural", "apply", "pivot",
            "unpivot", "with", "within", "filter", "into", "lateral", "rows", "range", "partition", "escape", "collate");
    private static final Pattern AFTER_PAREN = Pattern.compile("\\)\\s+" + IDENT);
    private static final Pattern FUNCTION_CALL = Pattern.compile("([A-Za-z_][\\w$#]*(?:\\.[A-Za-z_][\\w$#]*)*)\\s*\\(");

    private SqlLexer() {
    }

    /** Replace comments with a space and string literals with ''. Quoted identifiers are kept. */
    static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
                out.append(' ');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (c == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') i += 2;
                    else if (sql.charAt(i) == '\\' && i + 1 < n) i += 2;
                    else if (sql.charAt(i) == '\'') break;
                    else i++;
                }
                i++;
                out.append("''");
            } else if (c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                int end = sql.indexOf(close, i + 1);
                end = end < 0 ? n - 1 : end;
                out.append(sql, i, end + 1);
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Also blank out quoted identifiers, for keyword scans. */
    static String keywordsOnly(String stripped) {
        return stripped.replaceAll("\"[^\"]*\"|`[^`]*`|\\[[^\\]]*\\]", " q ");
    }

    static Set<String> cteNames(String stripped) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = CTE.matcher(stripped);
        while (m.find()) out.add(unquote(m.group(1)).toLowerCase(Locale.ROOT));
        return out;
    }

    /** Names the query itself defines: AS aliases, CTE column lists, implicit aliases after a closing parenthesis. */
    static Set<String> definedNames(String stripped) {
        Set<String> out = new LinkedHashSet<>();
        Matcher a = AS_ALIAS.matcher(stripped);
        while (a.find()) out.add(unquote(a.group(1)).toLowerCase(Locale.ROOT));
        Matcher p = AFTER_PAREN.matcher(stripped);
        while (p.find()) {
            String name = unquote(p.group(1)).toLowerCase(Locale.ROOT);
            if (!NOT_ALIAS.contains(name)) out.add(name);
        }
        Matcher c = CTE.matcher(stripped);
        while (c.find()) {
            if (c.group(2) != null) {
                for (String col : c.group(2).substring(1, c.group(2).length() - 1).split(",")) {
                    if (!col.isBlank()) out.add(unquote(col.strip()).toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    static Set<String> functionNames(String keywordsOnly) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = FUNCTION_CALL.matcher(keywordsOnly);
        while (m.find()) out.add(m.group(1).toLowerCase(Locale.ROOT));
        return out;
    }

    static String unquote(String id) {
        if (id == null) return null;
        String s = id.strip();
        if (s.length() >= 2) {
            char f = s.charAt(0);
            char l = s.charAt(s.length() - 1);
            if ((f == '"' && l == '"') || (f == '`' && l == '`') || (f == '[' && l == ']')) return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
