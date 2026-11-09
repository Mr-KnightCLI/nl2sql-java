package io.nl2sql.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nl2sql.core.ConfigurationException;
import io.nl2sql.core.LlmException;
import io.nl2sql.core.config.LlmSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Native Anthropic Messages API. */
public class AnthropicProvider extends HttpLlmSupport {
    private final LlmSettings settings;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final String url;

    public AnthropicProvider(LlmSettings settings, ObjectMapper json) {
        super(json, settings.getTimeoutSeconds());
        if (settings.getApiKey() == null || settings.getApiKey().isBlank()) {
            throw new ConfigurationException("The Anthropic provider needs nl2sql.llm.api-key (NL2SQL_LLM_API_KEY)");
        }
        this.settings = settings;
        String base = OpenAiCompatibleProvider.normalizeBaseUrl(settings.getBaseUrl());
        if (base.isEmpty() || base.contains("openai.com")) base = "https://api.anthropic.com/v1"; // default not changed
        this.url = base + "/messages";
        headers.put("x-api-key", settings.getApiKey().trim());
        headers.put("anthropic-version", "2023-06-01");
        headers.putAll(settings.headerMap());
    }

    @Override
    public LlmResponse complete(List<Message> messages) {
        StringJoiner system = new StringJoiner("\n\n");
        List<Map<String, String>> chat = new ArrayList<>();
        for (Message m : messages) {
            if ("system".equals(m.role())) system.add(m.content());
            else chat.add(Map.of("role", m.role(), "content", m.content()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", settings.getModel());
        payload.put("max_tokens", settings.getMaxTokens());
        payload.put("temperature", settings.getTemperature());
        payload.put("messages", chat);
        if (system.length() > 0) payload.put("system", system.toString());
        JsonNode data = post(url, headers, payload);
        JsonNode content = data.path("content");
        if (!content.isArray()) {
            throw new LlmException("Unexpected response from Anthropic: " + truncate(data.toString(), 300));
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
        return new LlmResponse(text.toString(), data.path("model").asText(settings.getModel()));
    }
}
