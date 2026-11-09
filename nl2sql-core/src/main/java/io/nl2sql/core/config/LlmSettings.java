package io.nl2sql.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which LLM to call. {@code provider=openai} speaks the OpenAI-compatible Chat Completions API, which also
 * covers Google Gemini, Azure OpenAI, Mistral, Groq, DeepSeek, OpenRouter, Ollama, LM Studio, vLLM and most
 * gateways - just change {@code baseUrl}. {@code provider=anthropic} uses the native Anthropic Messages API.
 */
public class LlmSettings {
    private String provider = "openai";
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey;
    private String model = "gpt-4.1-mini";
    private double temperature = 0.0;
    private int maxTokens = 1500;
    private int timeoutSeconds = 60;
    /** Extra HTTP headers for gateways: "Name: value; Other-Name: value". */
    private String headers;

    public Map<String, String> headerMap() {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) return out;
        for (String part : headers.split(";")) {
            int c = part.indexOf(':');
            if (c > 0 && !part.substring(c + 1).isBlank()) {
                out.put(part.substring(0, c).trim(), part.substring(c + 1).trim());
            }
        }
        return out;
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getHeaders() { return headers; }
    public void setHeaders(String headers) { this.headers = headers; }

    @Override
    public String toString() {
        return "LlmSettings[provider=" + provider + ", baseUrl=" + baseUrl + ", model=" + model
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "" : "<set>") + "]";
    }
}
