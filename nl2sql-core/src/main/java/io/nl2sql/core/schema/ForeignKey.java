package io.nl2sql.core.schema;

import java.util.List;

public record ForeignKey(List<String> columns, String refTable, List<String> refColumns) {
}
