package io.nl2sql.core.schema;

import java.util.ArrayList;
import java.util.List;

/** A column as shown to the LLM. {@code physicalName} is the exact name in the database. */
public class Column {
    private final String name;
    private final String physicalName;
    private final String type;
    private final boolean nullable;
    private final boolean primaryKey;
    private String description;
    private List<String> sampleValues = new ArrayList<>();

    public Column(String name, String physicalName, String type, boolean nullable, boolean primaryKey, String description) {
        this.name = name;
        this.physicalName = physicalName;
        this.type = type;
        this.nullable = nullable;
        this.primaryKey = primaryKey;
        this.description = description == null ? "" : description;
    }

    public String name() { return name; }
    public String physicalName() { return physicalName; }
    public String type() { return type; }
    public boolean nullable() { return nullable; }
    public boolean primaryKey() { return primaryKey; }
    public String description() { return description; }
    public void description(String d) { this.description = d == null ? "" : d; }
    public List<String> sampleValues() { return sampleValues; }
    public void sampleValues(List<String> v) { this.sampleValues = new ArrayList<>(v); }
}
