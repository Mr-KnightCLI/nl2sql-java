package io.nl2sql.core.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nl2sql.core.SchemaException;
import io.nl2sql.core.config.Dialect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/** Introspected schema plus the business meaning (descriptions, synonyms, glossary) added on top. */
public class SchemaCatalog {
    private final Dialect dialect;
    private final String schema;
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final Map<String, String> glossary = new LinkedHashMap<>();

    public SchemaCatalog(Dialect dialect, String schema) {
        this.dialect = dialect;
        this.schema = schema;
    }

    public Dialect dialect() { return dialect; }
    public String schema() { return schema; }
    public Map<String, Table> tables() { return tables; }
    public Map<String, String> glossary() { return glossary; }

    public void add(Table table) {
        tables.put(table.name(), table);
    }

    public Table table(String name) {
        if (name == null) return null;
        Table exact = tables.get(name);
        if (exact != null) return exact;
        String n = name.toLowerCase(Locale.ROOT);
        for (Table t : tables.values()) {
            if (t.name().toLowerCase(Locale.ROOT).equals(n)) return t;
        }
        return null;
    }

    public boolean hasColumn(String table, String column) {
        Table t = table(table);
        return t != null && t.column(column) != null;
    }

    // ---------------------------------------------------------------- semantic layer

    /** Attach a description to "table" or "table.column". */
    public void describe(String target, String description, List<String> synonyms) {
        int dot = target.indexOf('.');
        if (dot > 0) {
            Table t = table(target.substring(0, dot));
            Column c = t == null ? null : t.column(target.substring(dot + 1));
            if (c == null) throw new SchemaException("Unknown column: " + target);
            c.description(description);
        } else {
            Table t = table(target);
            if (t == null) throw new SchemaException("Unknown table: " + target);
            if (description != null && !description.isBlank()) t.description(description);
            if (synonyms != null) {
                for (String s : synonyms) if (!t.synonyms().contains(s)) t.synonyms().add(s);
            }
        }
    }

    public void addGlossary(String term, String definition) {
        glossary.put(term, definition);
    }

    /**
     * Load a semantic-layer JSON file (same format as the Python version):
     * <pre>{"tables": {"orders": {"description": "...", "synonyms": ["sales"], "columns": {"stat": "..."}}},
     *  "glossary": {"on-time": "delivered_at &lt;= promised_at"}}</pre>
     * Entries for tables or columns that are not exposed are ignored.
     */
    public void loadSemantics(Path path, ObjectMapper json) {
        JsonNode root;
        try {
            root = json.readTree(Files.readString(path));
        } catch (IOException e) {
            throw new SchemaException("Cannot read semantics file " + path + ": " + e.getMessage(), e);
        }
        JsonNode ts = root.path("tables");
        for (Iterator<Map.Entry<String, JsonNode>> it = ts.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            Table t = table(e.getKey());
            if (t == null) continue;
            JsonNode meta = e.getValue();
            List<String> syn = new ArrayList<>();
            meta.path("synonyms").forEach(n -> syn.add(n.asText()));
            describe(t.name(), meta.path("description").asText(""), syn);
            for (Iterator<Map.Entry<String, JsonNode>> cs = meta.path("columns").fields(); cs.hasNext(); ) {
                Map.Entry<String, JsonNode> c = cs.next();
                if (t.column(c.getKey()) != null) describe(t.name() + "." + c.getKey(), c.getValue().asText(""), null);
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> gs = root.path("glossary").fields(); gs.hasNext(); ) {
            Map.Entry<String, JsonNode> g = gs.next();
            addGlossary(g.getKey(), g.getValue().asText(""));
        }
    }

    // ---------------------------------------------------------------- prompt rendering

    /** Compact DDL with comments, the form LLMs understand best. */
    public String render(List<String> tableNames) {
        List<String> names = tableNames == null ? new ArrayList<>(tables.keySet()) : tableNames;
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            Table t = tables.get(name);
            if (t == null) continue;
            StringBuilder header = new StringBuilder();
            if (!t.description().isBlank()) header.append("-- ").append(t.description());
            if (!t.synonyms().isEmpty()) {
                header.append(header.length() == 0 ? "-- " : " ").append("(also called: ")
                        .append(String.join(", ", t.synonyms())).append(")");
            }
            if (header.length() > 0) out.append(header).append('\n');
            out.append("CREATE TABLE ").append(t.name()).append(" (\n");
            List<String[]> items = new ArrayList<>();
            for (Column c : t.columns()) {
                StringBuilder def = new StringBuilder("  ").append(c.name()).append(' ').append(c.type());
                if (c.primaryKey()) def.append(" PRIMARY KEY");
                else if (!c.nullable()) def.append(" NOT NULL");
                StringJoiner comment = new StringJoiner("; ");
                if (!c.description().isBlank()) comment.add(c.description());
                if (!c.sampleValues().isEmpty()) {
                    StringJoiner samples = new StringJoiner(", ", "e.g. ", "");
                    c.sampleValues().forEach(v -> samples.add("'" + v.replace("'", "''") + "'"));
                    comment.add(samples.toString());
                }
                items.add(new String[]{def.toString(), comment.toString()});
            }
            for (ForeignKey fk : t.foreignKeys()) {
                items.add(new String[]{"  FOREIGN KEY (" + String.join(", ", fk.columns()) + ") REFERENCES "
                        + fk.refTable() + "(" + String.join(", ", fk.refColumns()) + ")", ""});
            }
            for (int i = 0; i < items.size(); i++) {
                out.append(items.get(i)[0]);
                if (i < items.size() - 1) out.append(',');
                if (!items.get(i)[1].isEmpty()) out.append("  -- ").append(items.get(i)[1]);
                out.append('\n');
            }
            out.append(");\n\n");
        }
        return out.toString().strip();
    }

    /** Plain structure for the REST API (/v1/schema). */
    public Map<String, Object> toMap() {
        Map<String, Object> ts = new LinkedHashMap<>();
        for (Table t : tables.values()) {
            List<Map<String, Object>> cols = new ArrayList<>();
            for (Column c : t.columns()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name());
                m.put("type", c.type());
                m.put("nullable", c.nullable());
                m.put("primary_key", c.primaryKey());
                m.put("description", c.description());
                m.put("sample_values", c.sampleValues());
                cols.add(m);
            }
            List<Map<String, Object>> fks = new ArrayList<>();
            for (ForeignKey fk : t.foreignKeys()) {
                fks.add(Map.of("columns", fk.columns(), "ref_table", fk.refTable(), "ref_columns", fk.refColumns()));
            }
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("name", t.name());
            tm.put("description", t.description());
            tm.put("synonyms", t.synonyms());
            tm.put("columns", cols);
            tm.put("foreign_keys", fks);
            ts.put(t.name(), tm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dialect", dialect.name().toLowerCase(Locale.ROOT));
        out.put("db_schema", schema);
        out.put("glossary", glossary);
        out.put("tables", ts);
        return out;
    }
}
