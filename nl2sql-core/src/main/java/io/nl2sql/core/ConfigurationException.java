package io.nl2sql.core;

/** Missing or invalid settings (database, LLM, guardrails). */
public class ConfigurationException extends Nl2SqlException {
    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
