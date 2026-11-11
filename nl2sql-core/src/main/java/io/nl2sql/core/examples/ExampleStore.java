package io.nl2sql.core.examples;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.nl2sql.core.Nl2SqlException;
import io.nl2sql.core.schema.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Few-shot examples, retrieved by similarity to the incoming question. Persisted as JSON in the same format as
 * the Python version: {@code [{"question": "...", "sql": "...", "notes": ""}]}.
 */
public class ExampleStore {
    private final List<Example> examples = new ArrayList<>();
    private final Path path;
    private final ObjectMapper json;

    public ExampleStore(Path path, ObjectMapper json) {
        this.path = path;
        this.json = json;
        if (path != null && Files.exists(path)) load(path);
    }

    public synchronized int size() {
        return examples.size();
    }

    public synchronized List<Example> all() {
        return List.copyOf(examples);
    }

    public synchronized void add(String question, String sql, String notes) {
        Example example = new Example(question.strip(), sql.strip(), notes);
        if (path != null) {
            List<Example> updated = new ArrayList<>(examples);
            updated.add(example);
            save(path, updated);
        }
        // Failed persistence must not expose an example to search or a later save.
        examples.add(example);
    }

    /** Jaccard similarity on word tokens; override for embedding search. */
    public synchronized List<Example> search(String question, int k) {
        Set<String> q = Text.tokens(question);
        if (q.isEmpty()) return List.of();
        record Hit(double sim, Example ex) { }
        List<Hit> hits = new ArrayList<>();
        for (Example ex : examples) {
            Set<String> e = Text.tokens(ex.question());
            if (e.isEmpty()) continue;
            Set<String> inter = new HashSet<>(q);
            inter.retainAll(e);
            Set<String> union = new HashSet<>(q);
            union.addAll(e);
            double sim = (double) inter.size() / union.size();
            if (sim > 0) hits.add(new Hit(sim, ex));
        }
        hits.sort((a, b) -> Double.compare(b.sim(), a.sim()));
        return hits.stream().limit(k).map(Hit::ex).toList();
    }

    private void load(Path p) {
        try {
            List<Map<String, String>> raw = json.readValue(Files.readString(p), new TypeReference<>() { });
            for (Map<String, String> m : raw) {
                if (m.get("question") != null && m.get("sql") != null) {
                    examples.add(new Example(m.get("question"), m.get("sql"), m.get("notes")));
                }
            }
        } catch (IOException e) {
            throw new Nl2SqlException("Cannot read examples file " + p + ": " + e.getMessage(), e);
        }
    }

    private void save(Path p, List<Example> updated) {
        try {
            List<Map<String, String>> raw = new ArrayList<>();
            for (Example e : updated) raw.add(Map.of("question", e.question(), "sql", e.sql(), "notes", e.notes()));
            Files.writeString(p, json.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(raw));
        } catch (IOException e) {
            throw new Nl2SqlException("Cannot write examples file " + p + ": " + e.getMessage(), e);
        }
    }
}
