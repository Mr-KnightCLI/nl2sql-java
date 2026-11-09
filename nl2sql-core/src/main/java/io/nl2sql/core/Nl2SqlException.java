package io.nl2sql.core;

/** Base class for every error raised by nl2sql. */
public class Nl2SqlException extends RuntimeException {
    public Nl2SqlException(String message) {
        super(message);
    }

    public Nl2SqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
