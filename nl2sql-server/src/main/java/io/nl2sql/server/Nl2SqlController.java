package io.nl2sql.server;

import io.nl2sql.core.Nl2SqlEngine;
import io.nl2sql.core.QueryResult;
import io.nl2sql.server.ApiModels.AskRequest;
import io.nl2sql.server.ApiModels.AskResponse;
import io.nl2sql.server.ApiModels.ExampleRequest;
import io.nl2sql.server.ApiModels.GenerateResponse;
import io.nl2sql.server.ApiModels.InfoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
class Nl2SqlController {
    static final String VERSION = Nl2SqlController.class.getPackage().getImplementationVersion() == null
            ? "dev" : Nl2SqlController.class.getPackage().getImplementationVersion();

    private final Nl2SqlEngine engine;

    Nl2SqlController(Nl2SqlEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/")
    Map<String, Object> index() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "nl2sql");
        m.put("version", VERSION);
        m.put("api", "/openapi.yaml");
        m.put("health", "/health");
        return m;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/v1/info")
    InfoResponse info() {
        var cat = engine.catalog();
        var db = engine.settings().getDb();
        String name = db.getName();
        if (name != null && engine.dialect() == io.nl2sql.core.config.Dialect.SQLITE) {
            name = Path.of(name).getFileName().toString(); // never expose full paths
        }
        return new InfoResponse(name, engine.dialect().productName(), cat.schema(), cat.tables().size(), VERSION);
    }

    @GetMapping("/v1/schema")
    Map<String, Object> schema() {
        return engine.catalog().toMap();
    }

    @PostMapping("/v1/schema/refresh")
    Map<String, Object> refreshSchema() {
        engine.refreshSchema();
        return Map.of("tables", engine.catalog().tables().size());
    }

    @PostMapping("/v1/ask")
    AskResponse ask(@RequestBody AskRequest req) {
        QueryResult r = engine.ask(requireQuestion(req), req.turns());
        return AskResponse.of(r);
    }

    @PostMapping("/v1/generate")
    GenerateResponse generate(@RequestBody AskRequest req) {
        QueryResult r = engine.generate(requireQuestion(req), req.turns());
        return GenerateResponse.of(r);
    }

    @PostMapping("/v1/examples")
    ResponseEntity<Map<String, Object>> addExample(@RequestBody ExampleRequest req) {
        if (req == null || !ApiModels.notBlank(req.question()) || !ApiModels.notBlank(req.sql())) {
            throw new BadRequest("Both 'question' and 'sql' are required");
        }
        engine.addExample(req.question(), req.sql());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("stored", true, "count", engine.examples().size()));
    }

    private static String requireQuestion(AskRequest req) {
        if (req == null || !ApiModels.notBlank(req.question())) throw new BadRequest("'question' is required");
        if (req.question().length() > 4000) throw new BadRequest("'question' is longer than 4000 characters");
        return req.question();
    }

    static class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }
}
