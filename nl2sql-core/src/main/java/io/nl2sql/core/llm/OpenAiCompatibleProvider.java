package io.nl2sql.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nl2sql.core.LlmException;
import io.nl2sql.core.config.LlmSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI-compatible Chat Completions: OpenAI, Google Gemini, Azure OpenAI (v1 endpoints), Mistral, Groq, DeepSeek,
 * Together, OpenRouter, LiteLLM, Ollama, LM Studio, vLLM...
 */
public class OpenAiCompatibleProvider extends HttpLlmSupport {
    private final LlmSettings settings;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final String url;

    public OpenAiCompatibleProvider(LlmSettings settings, ObjectMapper json) {
        super(json, settings.getTimeoutSeconds());
        this.settings = settings;
        this.url = normalizeBaseUrl(settings.getBaseUrl()) + "/chat/completions";
        String key = settings.getApiKey();
        if (key != null && !key.isBlank()) {
            headers.put("Authorization", "Bearer " + key.trim());
            if (isAzureHost(settings.getBaseUrl())) headers.put("api-key", key.trim()); // Azure accepts either
        }
        headers.putAll(settings.headerMap());
    }

    @Override
    public LlmResponse complete(List<Message> messages) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", settings.getModel());
        List<Map<String, String>> msgs = new ArrayList<>();
        for (Message m : messages) msgs.add(Map.of("role", m.role(), "content", m.content()));
        payload.put("messages", msgs);
        payload.put("temperature", settings.getTemperature());
        payload.put("max_tokens", settings.getMaxTokens());
        JsonNode data = post(url, headers, payload);
        JsonNode content = data.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode()) {
            throw new LlmException("Unexpected response from the LLM: " + truncate(data.toString(), 300));
        }
        return new LlmResponse(content.isNull() ? "" : content.asText(), data.path("model").asText(settings.getModel()));
    }

    /** Accept a full endpoint URL by mistake: strip query and /chat/completions, /completions or /responses. */
    static String normalizeBaseUrl(String raw) {
        String base = raw == null ? "" : raw.trim();
        int q = base.indexOf('?');
        if (q >= 0) base = base.substring(0, q);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        for (String suffix : new String[]{"/chat/completions", "/completions", "/responses"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            }
        }
        return base;
    }

    static boolean isAzureHost(String url) {
        String rest = url == null ? "" : url.replaceFirst("^[a-zA-Z]+://", "");
        int slash = rest.indexOf('/');
        String host = (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(Locale.ROOT);
        return host.endsWith(".azure.com") || host.endsWith(".azure.us") || host.endsWith(".azure.cn");
    }
}
