package io.nl2sql.core.prompt;

public record ParsedResponse(String sql, String explanation, String clarification) {
}
