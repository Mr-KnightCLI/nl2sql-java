package io.nl2sql.core.llm;

/** One chat message: role is "system", "user" or "assistant". */
public record Message(String role, String content) {
    public static Message system(String content) { return new Message("system", content); }
    public static Message user(String content) { return new Message("user", content); }
    public static Message assistant(String content) { return new Message("assistant", content); }
}
