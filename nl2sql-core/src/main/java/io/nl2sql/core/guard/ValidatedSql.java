package io.nl2sql.core.guard;

import java.util.List;

/** SQL that passed every check, ready to run. */
public record ValidatedSql(String sql, List<String> tables) {
}
