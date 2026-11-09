package io.nl2sql.core.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Table {
    private final String name;
    private final String physicalName;
    private final List<Column> columns;
    private final List<ForeignKey> foreignKeys;
    private String description;
    private final List<String> synonyms = new ArrayList<>();

    public Table(String name, String physicalName, List<Column> columns, List<ForeignKey> foreignKeys, String description) {
        this.name = name;
        this.physicalName = physicalName;
        this.columns = columns;
        this.foreignKeys = foreignKeys;
        this.description = description == null ? "" : description;
    }

    public Column column(String columnName) {
        String n = columnName.toLowerCase(Locale.ROOT);
        for (Column c : columns) {
            if (c.name().toLowerCase(Locale.ROOT).equals(n)) return c;
        }
        return null;
    }

    public String name() { return name; }
    public String physicalName() { return physicalName; }
    public List<Column> columns() { return columns; }
    public List<ForeignKey> foreignKeys() { return foreignKeys; }
    public String description() { return description; }
    public void description(String d) { this.description = d == null ? "" : d; }
    public List<String> synonyms() { return synonyms; }
}
