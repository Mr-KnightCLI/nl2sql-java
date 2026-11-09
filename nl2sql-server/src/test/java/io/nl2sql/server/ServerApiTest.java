package io.nl2sql.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServerApiTest {
    static HttpServer llm;
    static volatile String nextSql = "SELECT name, qty FROM items ORDER BY qty DESC";
    static ConfigurableApplicationContext app;
    static String base;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        Path dir = Files.createTempDirectory("nl2sql-server-test");
        Path db = dir.resolve("shop.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT NOT NULL, qty INTEGER NOT NULL)");
            st.execute("INSERT INTO items VALUES (1,'bolt',40),(2,'nut',75),(3,'washer',12)");
        }
        llm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llm.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            String content = "{\\\"sql\\\": \\\"" + nextSql + "\\\", \\\"explanation\\\": \\\"ok\\\", \\\"clarification\\\": null}";
            byte[] b = ("{\"model\":\"mock\",\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        llm.start();
        app = SpringApplication.run(Nl2SqlServerApplication.class,
                "--server.port=0",
                "--nl2sql.db.type=sqlite",
                "--nl2sql.db.name=" + db,
                "--nl2sql.llm.base-url=http://127.0.0.1:" + llm.getAddress().getPort() + "/v1",
                "--nl2sql.llm.api-key=test",
                "--nl2sql.examples-path=" + dir.resolve("examples.json"),
                "--nl2sql.server.api-key=secret-key");
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
        if (llm != null) llm.stop(0);
    }

    static HttpResponse<String> call(String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        if (key != null) b.header("X-API-Key", key);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthIsOpenButApiNeedsKey() throws Exception {
        assertThat(call("GET", "/health", null, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/v1/info", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/v1/info", null, "wrong").statusCode()).isEqualTo(401);
        HttpResponse<String> info = call("GET", "/v1/info", null, "secret-key");
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(info.body()).contains("\"database_type\"").contains("shop.db").doesNotContain("nl2sql-server-test");
    }

    @Test
    void askReturnsRows() throws Exception {
        nextSql = "SELECT name, qty FROM items ORDER BY qty DESC";
        HttpResponse<String> r = call("POST", "/v1/ask", "{\"question\": \"stock by item\"}", "secret-key");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("\"row_count\":3").contains("\"tables_used\"").contains("nut");
    }

    @Test
    void generateAndSchema() throws Exception {
        nextSql = "SELECT COUNT(*) FROM items";
        HttpResponse<String> g = call("POST", "/v1/generate", "{\"question\": \"how many items\"}", "secret-key");
        assertThat(g.statusCode()).as(g.body()).isEqualTo(200);
        assertThat(g.body()).contains("SELECT COUNT(*) FROM items");
        HttpResponse<String> s = call("GET", "/v1/schema", null, "secret-key");
        assertThat(s.statusCode()).isEqualTo(200);
        assertThat(s.body()).contains("items").contains("qty");
    }

    @Test
    void examplesValidateSql() throws Exception {
        assertThat(call("POST", "/v1/examples", "{\"question\": \"q\", \"sql\": \"SELECT name FROM items\"}", "secret-key").statusCode())
                .isEqualTo(201);
        assertThat(call("POST", "/v1/examples", "{\"question\": \"q\", \"sql\": \"DELETE FROM items\"}", "secret-key").statusCode())
                .isEqualTo(400);
    }

    @Test
    void badRequests() throws Exception {
        assertThat(call("POST", "/v1/ask", "{}", "secret-key").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/v1/ask", "not json", "secret-key").statusCode()).isEqualTo(400);
        assertThat(call("GET", "/v1/ask", null, "secret-key").statusCode()).isEqualTo(405);
        assertThat(call("GET", "/v1/nothing", null, "secret-key").statusCode()).isEqualTo(404);
    }

    @Test
    void environmentMapping() {
        Map<String, String> env = Map.of("NL2SQL_DB_TYPE", "postgres", "NL2SQL_SERVER_PORT", "9000", "UNRELATED", "x");
        Map<String, String> props = EnvironmentMapping.toProperties(env);
        assertThat(props).containsEntry("nl2sql.db.type", "postgres").containsEntry("server.port", "9000").hasSize(2);
    }
}
