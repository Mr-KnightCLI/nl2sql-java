package io.nl2sql.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.nl2sql.core.LlmException;
import io.nl2sql.core.config.LlmSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmProvidersTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private volatile JsonNode lastBody;
    private volatile String lastPath;
    private volatile int status = 200;
    private volatile String reply;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            lastPath = ex.getRequestURI().getPath();
            ex.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), v.get(0)));
            lastBody = json.readTree(ex.getRequestBody().readAllBytes());
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void openAiCompatibleRequestShape() {
        reply = "{\"model\":\"m1\",\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";
        LlmSettings s = new LlmSettings();
        s.setBaseUrl(base() + "/v1/chat/completions"); // full endpoint pasted by mistake
        s.setApiKey("k-123");
        s.setModel("m1");
        s.setHeaders("X-Team: data");
        LlmResponse r = LlmProviders.create(s, json).complete(List.of(Message.system("sys"), Message.user("q")));
        assertThat(r.text()).isEqualTo("hello");
        assertThat(lastPath).isEqualTo("/v1/chat/completions");
        assertThat(lastHeaders).containsEntry("authorization", "Bearer k-123").containsEntry("x-team", "data");
        assertThat(lastBody.path("model").asText()).isEqualTo("m1");
        assertThat(lastBody.path("messages")).hasSize(2);
    }

    @Test
    void anthropicRequestShape() {
        reply = "{\"model\":\"c\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}";
        LlmSettings s = new LlmSettings();
        s.setProvider("anthropic");
        s.setBaseUrl(base());
        s.setApiKey("ak");
        s.setModel("c");
        LlmResponse r = LlmProviders.create(s, json).complete(List.of(Message.system("sys"), Message.user("q")));
        assertThat(r.text()).isEqualTo("hi");
        assertThat(lastPath).endsWith("/messages");
        assertThat(lastHeaders).containsEntry("x-api-key", "ak").containsKey("anthropic-version");
        assertThat(lastBody.path("system").asText()).isEqualTo("sys");
        assertThat(lastBody.path("messages")).hasSize(1);
    }

    @Test
    void errorsAreDescribedWithoutRetryOn4xx() {
        status = 401;
        reply = "{\"error\":{\"message\":\"Incorrect API key\"}}";
        LlmSettings s = new LlmSettings();
        s.setBaseUrl(base() + "/v1");
        s.setApiKey("bad");
        LlmProvider p = LlmProviders.create(s, json);
        assertThatThrownBy(() -> p.complete(List.of(Message.user("q"))))
                .isInstanceOf(LlmException.class).hasMessageContaining("Incorrect API key");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void urlHelpers() {
        assertThat(OpenAiCompatibleProvider.normalizeBaseUrl("https://x/v1/chat/completions?api-version=1")).isEqualTo("https://x/v1");
        assertThat(OpenAiCompatibleProvider.normalizeBaseUrl("https://x/v1/")).isEqualTo("https://x/v1");
        assertThat(OpenAiCompatibleProvider.isAzureHost("https://me.openai.azure.com/openai/v1")).isTrue();
        assertThat(OpenAiCompatibleProvider.isAzureHost("https://api.openai.com/v1")).isFalse();
    }
}
