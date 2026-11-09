package io.nl2sql.core.db;

import java.util.List;

public record ExecutionResult(List<String> columns, List<List<Object>> rows, boolean truncated, double elapsedMs) {
}
