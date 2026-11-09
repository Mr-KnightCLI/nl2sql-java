package io.nl2sql.server;

import io.nl2sql.core.ConfigurationException;
import io.nl2sql.core.GuardrailViolation;
import io.nl2sql.core.LlmException;
import io.nl2sql.core.Nl2SqlException;
import io.nl2sql.core.SchemaException;
import io.nl2sql.server.ApiModels.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Every error is returned as {"error": "..."} with a meaningful status code. */
@RestControllerAdvice
class ErrorHandling {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandling.class);

    @ExceptionHandler(Nl2SqlController.BadRequest.class)
    ResponseEntity<ErrorResponse> badRequest(Nl2SqlController.BadRequest e) {
        return status(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return status(HttpStatus.BAD_REQUEST, "The request body must be valid JSON, for example {\"question\": \"...\"}");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> mediaType(HttpMediaTypeNotSupportedException e) {
        return status(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the body as JSON with Content-Type: application/json");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return status(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return status(HttpStatus.NOT_FOUND, "Not found. See /openapi.yaml for the available endpoints.");
    }

    @ExceptionHandler(GuardrailViolation.class)
    ResponseEntity<ErrorResponse> guardrail(GuardrailViolation e) {
        return status(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(LlmException.class)
    ResponseEntity<ErrorResponse> llm(LlmException e) {
        return status(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(SchemaException.class)
    ResponseEntity<ErrorResponse> schema(SchemaException e) {
        return status(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(ConfigurationException.class)
    ResponseEntity<ErrorResponse> config(ConfigurationException e) {
        return status(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    @ExceptionHandler(Nl2SqlException.class)
    ResponseEntity<ErrorResponse> other(Nl2SqlException e) {
        return status(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return status(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error: " + e.getClass().getSimpleName());
    }

    private static ResponseEntity<ErrorResponse> status(HttpStatus s, String message) {
        return ResponseEntity.status(s).body(new ErrorResponse(message));
    }
}
