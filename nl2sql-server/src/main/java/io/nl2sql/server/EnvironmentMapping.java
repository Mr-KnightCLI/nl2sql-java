package io.nl2sql.server;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the documented {@code NL2SQL_*} environment variables onto properties. Spring's relaxed binding cannot
 * map names like {@code NL2SQL_LLM_API_KEY} to {@code nl2sql.llm.api-key} by itself, so the mapping is explicit.
 * Values become system properties, which rank above the properties file: environment variables win, as is usual
 * for services and containers. A {@code -D} system property set on the command line is never overwritten.
 */
public final class EnvironmentMapping {
    public static final Map<String, String> ENV_TO_PROPERTY = new LinkedHashMap<>();

    static {
        // database
        ENV_TO_PROPERTY.put("NL2SQL_DB_TYPE", "nl2sql.db.type");
        ENV_TO_PROPERTY.put("NL2SQL_DB_HOST", "nl2sql.db.host");
        ENV_TO_PROPERTY.put("NL2SQL_DB_PORT", "nl2sql.db.port");
        ENV_TO_PROPERTY.put("NL2SQL_DB_NAME", "nl2sql.db.name");
        ENV_TO_PROPERTY.put("NL2SQL_DB_SCHEMA", "nl2sql.db.schema");
        ENV_TO_PROPERTY.put("NL2SQL_DB_USERNAME", "nl2sql.db.username");
        ENV_TO_PROPERTY.put("NL2SQL_DB_PASSWORD", "nl2sql.db.password");
        ENV_TO_PROPERTY.put("NL2SQL_DB_OPTIONS", "nl2sql.db.options");
        ENV_TO_PROPERTY.put("NL2SQL_DB_URL", "nl2sql.db.url");
        ENV_TO_PROPERTY.put("NL2SQL_DB_POOL_SIZE", "nl2sql.db.pool-size");
        ENV_TO_PROPERTY.put("NL2SQL_INCLUDE_TABLES", "nl2sql.db.include-tables");
        ENV_TO_PROPERTY.put("NL2SQL_EXCLUDE_TABLES", "nl2sql.db.exclude-tables");
        ENV_TO_PROPERTY.put("NL2SQL_SAMPLE_VALUES", "nl2sql.db.sample-values");
        // LLM
        ENV_TO_PROPERTY.put("NL2SQL_LLM_PROVIDER", "nl2sql.llm.provider");
        ENV_TO_PROPERTY.put("NL2SQL_LLM_BASE_URL", "nl2sql.llm.base-url");
        ENV_TO_PROPERTY.put("NL2SQL_LLM_API_KEY", "nl2sql.llm.api-key");
        ENV_TO_PROPERTY.put("NL2SQL_LLM_MODEL", "nl2sql.llm.model");
        ENV_TO_PROPERTY.put("NL2SQL_LLM_TEMPERATURE", "nl2sql.llm.temperature");
        ENV_TO_PROPERTY.put("NL2SQL_LLM_MAX_TOKENS", "nl2sql.llm.max-tokens");
        ENV_TO_PROPERTY.put("NL2SQL_LLM_TIMEOUT", "nl2sql.llm.timeout-seconds");
        ENV_TO_PROPERTY.put("NL2SQL_LLM_HEADERS", "nl2sql.llm.headers");
        // guardrails
        ENV_TO_PROPERTY.put("NL2SQL_MAX_ROWS", "nl2sql.guardrails.max-rows");
        ENV_TO_PROPERTY.put("NL2SQL_STATEMENT_TIMEOUT", "nl2sql.guardrails.statement-timeout-seconds");
        ENV_TO_PROPERTY.put("NL2SQL_BLOCKED_COLUMNS", "nl2sql.guardrails.blocked-columns");
        ENV_TO_PROPERTY.put("NL2SQL_ALLOW_SELECT_STAR", "nl2sql.guardrails.allow-select-star");
        ENV_TO_PROPERTY.put("NL2SQL_MAX_CORRECTION_ATTEMPTS", "nl2sql.guardrails.max-correction-attempts");
        // knowledge files
        ENV_TO_PROPERTY.put("NL2SQL_SEMANTICS_PATH", "nl2sql.semantics-path");
        ENV_TO_PROPERTY.put("NL2SQL_EXAMPLES_PATH", "nl2sql.examples-path");
        ENV_TO_PROPERTY.put("NL2SQL_EXTRA_INSTRUCTIONS", "nl2sql.extra-instructions");
        // server
        ENV_TO_PROPERTY.put("NL2SQL_SERVER_API_KEY", "nl2sql.server.api-key");
        ENV_TO_PROPERTY.put("NL2SQL_SERVER_HOST", "server.address");
        ENV_TO_PROPERTY.put("NL2SQL_SERVER_PORT", "server.port");
    }

    private EnvironmentMapping() {
    }

    /** The Spring properties that the given environment variables set (blank values are ignored). */
    public static Map<String, String> toProperties(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        ENV_TO_PROPERTY.forEach((var, property) -> {
            String value = env.get(var);
            if (value != null && !value.isBlank()) out.put(property, value.strip());
        });
        return out;
    }

    /** Copy them into system properties, without overriding -D options given on the command line. */
    public static void apply(Map<String, String> env) {
        toProperties(env).forEach((property, value) -> {
            if (System.getProperty(property) == null) System.setProperty(property, value);
        });
    }
}
