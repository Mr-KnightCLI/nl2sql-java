package io.nl2sql.core;

/** The LLM provider failed, timed out, or returned an unusable response. */
public class LlmException extends Nl2SqlException {
    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
