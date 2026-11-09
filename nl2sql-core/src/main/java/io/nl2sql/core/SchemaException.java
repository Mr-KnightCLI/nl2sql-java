package io.nl2sql.core;

/** Schema introspection failed or a referenced table/column does not exist. */
public class SchemaException extends Nl2SqlException {
    public SchemaException(String message) {
        super(message);
    }

    public SchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
