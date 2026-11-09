package io.nl2sql.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nl2sql.core.ConfigurationException;
import io.nl2sql.core.config.LlmSettings;

import java.util.Locale;

public final class LlmProviders {
    private LlmProviders() {
    }

    public static LlmProvider create(LlmSettings settings, ObjectMapper json) {
        String p = settings.getProvider() == null ? "openai" : settings.getProvider().trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "openai", "azure", "openai-compatible" -> new OpenAiCompatibleProvider(settings, json);
            case "anthropic", "claude" -> new AnthropicProvider(settings, json);
            default -> throw new ConfigurationException("Unknown LLM provider '" + settings.getProvider()
                    + "'. Use openai (any OpenAI-compatible endpoint) or anthropic.");
        };
    }
}
