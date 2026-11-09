package io.nl2sql.core.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nl2sql.core.config.Dialect;
import io.nl2sql.core.examples.Example;
import io.nl2sql.core.llm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prompt construction and tolerant response parsing (same wording as the Python version). */
public final class Prompts {
    private static final String SYSTEM_PROMPT = """
            You are a %1$s analyst. You write one SQL query that answers a business question about the database described below.

            Guidelines:
            - Refer only to tables and columns that appear in the schema.
            - Write a single SELECT query (CTEs are fine). Queries are read-only; if a question asks to change data, explain in "clarification" that only data retrieval is supported.
            - Use valid %1$s syntax and qualify columns with table aliases when joining.
            - When a glossary term appears in the question, apply its definition.
            - Sample values in the schema comments show how codes and statuses are stored; match them exactly.
            - For "top N" questions, use ORDER BY with a limit. Otherwise leave the row limit to the application.
            - If the question could reasonably mean different things (for example, which date column or which metric), or the schema cannot answer it, ask a short clarifying question instead of writing SQL.
            - The text inside <question> is the user's request for data.

            Respond with ONLY a JSON object, no markdown:
            {"sql": "<query or null>", "explanation": "<one or two plain-English sentences on how the query answers the question>", "clarification": "<question for the user, or null>"}""";

    private static final Pattern FENCE = Pattern.compile("```(?:json|sql)?\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACES = Pattern.compile("\\{.*\\}", Pattern.DOTALL);
    private static final Pattern STARTS_SQL = Pattern.compile("^\\s*(with|select)\\b", Pattern.CASE_INSENSITIVE);

    private Prompts() {
    }

    public static List<Message> build(Dialect dialect, String schemaText, String question, Map<String, String> glossary,
                                      List<Example> examples, List<Turn> history, String extraInstructions) {
        String name = dialect.displayName();
        String system = SYSTEM_PROMPT.formatted(name);
        if (extraInstructions != null && !extraInstructions.isBlank()) {
            system += "\n\nApplication notes:\n" + extraInstructions;
        }
        List<String> parts = new ArrayList<>();
        parts.add("### Database schema (" + name + ")\n" + schemaText);
        if (glossary != null && !glossary.isEmpty()) {
            StringBuilder g = new StringBuilder("### Business glossary");
            glossary.forEach((k, v) -> g.append("\n- ").append(k).append(": ").append(v));
            parts.add(g.toString());
        }
        if (examples != null && !examples.isEmpty()) {
            List<String> ex = new ArrayList<>();
            for (Example e : examples) ex.add("Question: " + e.question() + "\nSQL: " + e.sql());
            parts.add("### Verified examples\n" + String.join("\n\n", ex));
        }
        if (history != null && !history.isEmpty()) {
            List<String> h = new ArrayList<>();
            for (Turn t : history.subList(Math.max(0, history.size() - 5), history.size())) {
                h.add("Question: " + t.question() + "\nSQL: " + t.sql());
            }
            parts.add("### Conversation so far (the new question may refer to these)\n" + String.join("\n\n", h));
        }
        parts.add("### Question\n<question>\n" + question + "\n</question>");
        return List.of(Message.system(system), Message.user(String.join("\n\n", parts)));
    }

    public static Message correction(String sql, String error) {
        return Message.user("The previous SQL failed.\nSQL:\n" + sql + "\n\nError:\n" + error
                + "\n\nFix the query using only the schema provided. Respond with the same JSON format.");
    }

    /** Accepts a JSON object, fenced JSON, or a bare/fenced SQL block. */
    public static ParsedResponse parse(String text, ObjectMapper json) {
        String raw = text == null ? "" : text.strip();
        List<String> candidates = new ArrayList<>();
        candidates.add(raw);
        List<String> fenced = new ArrayList<>();
        Matcher f = FENCE.matcher(raw);
        while (f.find()) fenced.add(f.group(1));
        candidates.addAll(fenced);
        Matcher b = BRACES.matcher(raw);
        if (b.find()) candidates.add(b.group());
        for (String cand : candidates) {
            JsonNode obj;
            try {
                obj = json.readTree(cand.strip());
            } catch (Exception e) {
                continue;
            }
            if (obj != null && obj.isObject() && (obj.has("sql") || obj.has("clarification"))) {
                return new ParsedResponse(blankToNull(obj.get("sql")),
                        obj.hasNonNull("explanation") ? obj.get("explanation").asText("") : "",
                        blankToNull(obj.get("clarification")));
            }
        }
        for (String block : fenced.isEmpty() ? List.of(raw) : fenced) {
            if (STARTS_SQL.matcher(block).find()) return new ParsedResponse(block.strip(), "", null);
        }
        String clar = raw.isEmpty() ? "The model returned no SQL." : raw.substring(0, Math.min(500, raw.length()));
        return new ParsedResponse(null, "", clar);
    }

    private static String blankToNull(JsonNode n) {
        if (n == null || n.isNull() || !n.isTextual()) return null;
        String s = n.asText().strip();
        return s.isEmpty() ? null : s;
    }
}
