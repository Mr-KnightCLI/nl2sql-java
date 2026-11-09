package io.nl2sql.core.prompt;

/** An earlier question and the SQL that answered it, for follow-up questions. */
public record Turn(String question, String sql) {
}
