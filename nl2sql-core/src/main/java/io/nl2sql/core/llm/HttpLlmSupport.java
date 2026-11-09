package io.nl2sql.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nl2sql.core.LlmException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared HTTP plumbing for the built-in providers: retries, and readable errors. */
abstract class HttpLlmSupport implements LlmProvider {
    private static final Set<Integer> RETRY_STATUS = Set.of(408, 429, 500, 502, 503, 504);
    private static final String[] FILTER_MARKERS = {"content_filter", "content_policy", "responsibleaipolicyviolation",
            "safety", "blocked", "invalid_prompt", "moderation"};

    protected final HttpClient http;
    protected final ObjectMapper json;
    protected final Duration timeout;
    protected int retries = 3;

    HttpLlmSupport(ObjectMapper json, int timeoutSeconds) {
        this.json = json;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(20, timeoutSeconds))).build();
    }

    @Override
    public void setRetries(int retries) {
        this.retries = Math.max(1, retries);
    }

    protected JsonNode post(String url, Map<String, String> headers, Object payload) {
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (IOException e) {
            throw new LlmException("Cannot encode the LLM request: " + e.getMessage(), e);
        }
        Exception last = null;
        for (int attempt = 0; attempt < retries; attempt++) {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(req::header);
            try {
                HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() < 400) return json.readTree(resp.body());
                if (!RETRY_STATUS.contains(resp.statusCode())) {
                    throw new LlmException(describeError(resp.statusCode(), resp.body()));
                }
                last = new LlmException("HTTP " + resp.statusCode());
            } catch (LlmException e) {
                throw e;
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted while calling the LLM", e);
            }
            if (attempt < retries - 1) sleep(Math.min(1000L << attempt, 8000L));
        }
        throw new LlmException("LLM request failed after " + retries + " attempt" + (retries == 1 ? "" : "s") + ": "
                + (last == null ? "unknown error" : last.getClass().getSimpleName() + ": " + last.getMessage()));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Readable error text; content-safety blocks from any provider get actionable advice. */
    String describeError(int status, String bodyText) {
        JsonNode body;
        try {
            body = json.readTree(bodyText);
        } catch (IOException | RuntimeException e) {
            return "LLM request failed: HTTP " + status + ": " + truncate(bodyText, 500);
        }
        if (body != null && body.isArray() && body.size() > 0) body = body.get(0); // some gateways wrap errors
        JsonNode err = body == null ? null : (body.has("error") ? body.get("error") : body);
        if (err == null || !err.isObject()) {
            return "LLM request failed: HTTP " + status + ": " + truncate(bodyText, 500);
        }
        JsonNode inner = err.path("innererror");
        String code = firstNonBlank(text(err, "code"), text(inner, "code"), text(err, "type"), text(err, "status"));
        String message = text(err, "message");
        String probe = (code + " " + text(inner, "code") + " " + truncate(message, 200)).toLowerCase(Locale.ROOT);
        boolean filtered = (status == 400 || status == 403 || status == 422);
        boolean marker = false;
        for (String m : FILTER_MARKERS) if (probe.contains(m)) marker = true;
        if (filtered && marker) {
            JsonNode result = inner.has("content_filter_result") ? inner.get("content_filter_result") : err.path("content_filter_result");
            List<String> hits = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = result.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().path("filtered").asBoolean(false) || e.getValue().path("detected").asBoolean(false)) {
                    hits.add(e.getKey());
                }
            }
            boolean pii = probe.contains("personal") || hits.stream().anyMatch(h -> h.contains("personal") || h.contains("pii"));
            String advice = pii
                    ? "The prompt contained text the provider classified as personal data - usually sample values read "
                    + "from your tables (names, emails, phone numbers). Set nl2sql.db.sample-values=false "
                    + "(NL2SQL_SAMPLE_VALUES=false), or hide those columns with nl2sql.guardrails.blocked-columns, "
                    + "or relax the PII filter in your provider's content-safety settings."
                    : "Text-to-SQL prompts (schema text, rules, data values) can trigger content filters by mistake. "
                    + "Try nl2sql.db.sample-values=false (NL2SQL_SAMPLE_VALUES=false), or adjust the filter in your "
                    + "provider's content-safety settings (e.g. Azure AI Foundry > Guardrails + controls).";
            return "The LLM provider's content filter blocked the request (HTTP " + status
                    + (hits.isEmpty() ? "" : "; triggered: " + String.join(", ", hits)) + "). " + advice;
        }
        return "LLM request failed: HTTP " + status + ": " + (code.isEmpty() ? "" : code + ": ")
                + (message.isEmpty() ? truncate(bodyText, 500) : message);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}
