package io.nl2sql.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Requires the X-API-Key header on /v1/* when nl2sql.server.api-key is set. */
@Component
class ApiKeyFilter extends OncePerRequestFilter {
    private final byte[] expected;

    ApiKeyFilter(Nl2SqlProperties props) {
        String key = props.getServer().getApiKey();
        this.expected = key == null || key.isBlank() ? null : key.strip().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return expected == null || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader("X-API-Key");
        byte[] actual = given == null ? new byte[0] : given.strip().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or missing X-API-Key header\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
