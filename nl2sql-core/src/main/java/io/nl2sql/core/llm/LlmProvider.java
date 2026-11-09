package io.nl2sql.core.llm;

import java.util.List;

/** Minimal contract: chat messages in, text out. Implement it to plug in any model. */
public interface LlmProvider {
    LlmResponse complete(List<Message> messages);

    /** How many attempts transient failures get (429, 5xx, timeouts). */
    default void setRetries(int retries) {
    }
}
