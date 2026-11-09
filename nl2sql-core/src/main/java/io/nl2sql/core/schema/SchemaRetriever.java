package io.nl2sql.core.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks the tables relevant to a question: lexical scoring on names, descriptions, synonyms and sample values,
 * then one hop of foreign-key expansion so join paths stay complete. Small schemas are sent whole.
 * Subclass and override {@link #score} to plug in embeddings.
 */
public class SchemaRetriever {
    private final SchemaCatalog catalog;
    private final int maxTables;
    private final int smallSchemaThreshold;

    public SchemaRetriever(SchemaCatalog catalog) {
        this(catalog, 8, 15);
    }

    public SchemaRetriever(SchemaCatalog catalog, int maxTables, int smallSchemaThreshold) {
        this.catalog = catalog;
        this.maxTables = maxTables;
        this.smallSchemaThreshold = smallSchemaThreshold;
    }

    protected double score(String question, Table t) {
        Set<String> q = Text.tokens(question);
        Set<String> nameTokens = Text.tokens(t.name() + " " + String.join(" ", t.synonyms()));
        StringBuilder doc = new StringBuilder(t.name()).append(' ').append(t.description()).append(' ')
                .append(String.join(" ", t.synonyms()));
        for (Column c : t.columns()) {
            doc.append(' ').append(c.name()).append(' ').append(c.description()).append(' ')
                    .append(String.join(" ", c.sampleValues()));
        }
        Set<String> d = Text.tokens(doc.toString());
        return 3.0 * intersect(q, nameTokens) + intersect(q, d);
    }

    public List<String> select(String question) {
        List<Table> tables = new ArrayList<>(catalog.tables().values());
        if (tables.size() <= smallSchemaThreshold) return tables.stream().map(Table::name).toList();
        record Scored(double score, String name) { }
        List<Scored> scored = new ArrayList<>();
        for (Table t : tables) scored.add(new Scored(score(question, t), t.name()));
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<String> chosen = new ArrayList<>();
        for (Scored s : scored) if (s.score() > 0 && chosen.size() < maxTables) chosen.add(s.name());
        if (chosen.isEmpty()) for (int i = 0; i < Math.min(maxTables, scored.size()); i++) chosen.add(scored.get(i).name());
        List<String> expanded = new ArrayList<>(chosen);
        for (String name : chosen) {
            for (ForeignKey fk : catalog.tables().get(name).foreignKeys()) {
                Table ref = catalog.table(fk.refTable());
                if (ref != null && !expanded.contains(ref.name())) expanded.add(ref.name());
            }
        }
        return expanded.subList(0, Math.min(expanded.size(), maxTables + 4));
    }

    private static int intersect(Set<String> a, Set<String> b) {
        Set<String> s = new HashSet<>(a);
        s.retainAll(b);
        return s.size();
    }
}
