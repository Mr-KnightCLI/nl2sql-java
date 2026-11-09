package io.nl2sql.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.nl2sql.core.config.Dialect;
import io.nl2sql.core.config.Nl2SqlSettings;
import io.nl2sql.core.db.DataSourceFactory;
import io.nl2sql.core.db.ExecutionResult;
import io.nl2sql.core.db.QueryExecutor;
import io.nl2sql.core.examples.ExampleStore;
import io.nl2sql.core.guard.SqlGuard;
import io.nl2sql.core.guard.ValidatedSql;
import io.nl2sql.core.llm.LlmProvider;
import io.nl2sql.core.llm.LlmProviders;
import io.nl2sql.core.llm.LlmResponse;
import io.nl2sql.core.llm.Message;
import io.nl2sql.core.prompt.ParsedResponse;
import io.nl2sql.core.prompt.Prompts;
import io.nl2sql.core.prompt.Turn;
import io.nl2sql.core.schema.SchemaCatalog;
import io.nl2sql.core.schema.SchemaIntrospector;
import io.nl2sql.core.schema.SchemaRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates plain-English questions into safe, validated SQL and runs it.
 *
 * <pre>{@code
 * Nl2SqlSettings s = new Nl2SqlSettings();
 * s.getDb().setType("postgresql"); s.getDb().setHost("db"); s.getDb().setName("erp");
 * s.getDb().setUsername("readonly"); s.getDb().setPassword("...");
 * s.getLlm().setApiKey("sk-...");
 * try (Nl2SqlEngine engine = Nl2SqlEngine.create(s)) {
 *     QueryResult r = engine.ask("Top 5 customers by revenue last quarter", List.of());
 * }
 * }</pre>
 */
public class Nl2SqlEngine implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Nl2SqlEngine.class);

    private final Nl2SqlSettings settings;
    private final DataSource dataSource;
    private final boolean ownsDataSource;
    private final Dialect dialect;
    private final LlmProvider llm;
    private final ObjectMapper json;
    private final ExampleStore examples;
    private volatile SchemaCatalog catalog;

    public Nl2SqlEngine(Nl2SqlSettings settings, DataSource dataSource, LlmProvider llm, boolean ownsDataSource) {
        this.settings = settings;
        this.dataSource = dataSource;
        this.ownsDataSource = ownsDataSource;
        this.dialect = settings.getDb().dialect();
        this.llm = llm;
        this.json = defaultJson();
        this.examples = new ExampleStore(pathOrNull(settings.getExamplesPath()), json);
    }

    /** Engine with its own connection pool and the configured LLM provider. */
    public static Nl2SqlEngine create(Nl2SqlSettings settings) {
        LlmProvider llm = LlmProviders.create(settings.getLlm(), defaultJson());
        return create(settings, llm);
    }

    /** Engine with its own connection pool and a custom LLM provider. */
    public static Nl2SqlEngine create(Nl2SqlSettings settings, LlmProvider llm) {
        HikariDataSource ds = DataSourceFactory.create(settings.getDb(), settings.getGuardrails().getStatementTimeoutSeconds());
        return new Nl2SqlEngine(settings, ds, llm, true);
    }

    public static ObjectMapper defaultJson() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ------------------------------------------------------------------ schema

    /** Introspected and enriched schema; loaded on first use and cached. */
    public SchemaCatalog catalog() {
        SchemaCatalog c = catalog;
        if (c == null) {
            synchronized (this) {
                c = catalog;
                if (c == null) {
                    var db = settings.getDb();
                    c = new SchemaIntrospector(dataSource, dialect).introspect(db.getSchema(), db.getIncludeTables(),
                            db.getExcludeTables(), settings.getGuardrails().getBlockedColumns(), db.isSampleValues());
                    Path semantics = pathOrNull(settings.getSemanticsPath());
                    if (semantics != null) c.loadSemantics(semantics, json);
                    log.info("nl2sql: {} tables loaded from {}", c.tables().size(), dialect.productName());
                    catalog = c;
                }
            }
        }
        return c;
    }

    /** Re-read the schema after DDL changes. */
    public synchronized void refreshSchema() {
        catalog = null;
        catalog();
    }

    public Dialect dialect() { return dialect; }
    public Nl2SqlSettings settings() { return settings; }
    public ExampleStore examples() { return examples; }
    public LlmProvider llm() { return llm; }
    public DataSource dataSource() { return dataSource; }

    public SqlGuard guard() {
        return new SqlGuard(catalog(), settings.getGuardrails());
    }

    /** Store a verified question/SQL pair for few-shot prompting; the SQL is validated first. */
    public void addExample(String question, String sql) {
        guard().validate(sql);
        examples.add(question, sql, "");
    }

    // ------------------------------------------------------------------ ask

    /** Question to validated SQL, without running it. */
    public QueryResult generate(String question, List<Turn> history) {
        return run(question, history, false);
    }

    /** Question to validated SQL to rows. */
    public QueryResult ask(String question, List<Turn> history) {
        return run(question, history, true);
    }

    private QueryResult run(String rawQuestion, List<Turn> history, boolean execute) {
        String question = rawQuestion == null ? "" : rawQuestion.strip();
        if (question.isEmpty()) return QueryResult.failure(question, "", 0, "Empty question");

        SchemaCatalog cat = catalog();
        List<String> tables = new SchemaRetriever(cat).select(question);
        List<Message> messages = new ArrayList<>(Prompts.build(dialect, cat.render(tables), question, cat.glossary(),
                examples.search(question, 3), history, settings.getExtraInstructions()));
        SqlGuard guard = new SqlGuard(cat, settings.getGuardrails());
        var g = settings.getGuardrails();
        QueryExecutor executor = execute ? new QueryExecutor(dataSource, dialect, g.getStatementTimeoutSeconds(), g.getMaxRows()) : null;

        int maxAttempts = 1 + g.getMaxCorrectionAttempts();
        String lastError = null;
        String lastSql = null;
        ParsedResponse parsed = null;
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt++;
            LlmResponse response = llm.complete(messages);
            parsed = Prompts.parse(response.text(), json);
            if (parsed.clarification() != null && parsed.sql() == null) {
                return QueryResult.clarification(question, parsed.explanation(), parsed.clarification(), attempt);
            }
            if (parsed.sql() == null) {
                lastError = "The model returned no SQL";
                break;
            }
            lastSql = parsed.sql();
            try {
                ValidatedSql v = guard.validate(parsed.sql());
                if (executor == null) {
                    return new QueryResult(question, v.sql(), parsed.explanation(), null, v.tables(), attempt, null,
                            List.of(), List.of(), false, 0, false);
                }
                ExecutionResult r = executor.run(v.sql());
                return new QueryResult(question, v.sql(), parsed.explanation(), null, v.tables(), attempt, null,
                        r.columns(), r.rows(), r.truncated(), Math.round(r.elapsedMs() * 100) / 100.0, true);
            } catch (GuardrailViolation | QueryExecutionException e) {
                lastError = e.getMessage();
                log.info("nl2sql attempt {} failed: {}", attempt, lastError);
                messages.add(Message.assistant(response.text()));
                messages.add(Prompts.correction(parsed.sql(), lastError));
            }
        }
        return QueryResult.failure(question, parsed == null ? "" : parsed.explanation(), attempt,
                lastSql == null ? lastError : lastError + " (last SQL: " + lastSql + ")");
    }

    private static Path pathOrNull(String p) {
        return p == null || p.isBlank() ? null : Path.of(p.trim());
    }

    @Override
    public void close() {
        if (ownsDataSource && dataSource instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception e) {
                log.debug("Closing the pool failed: {}", e.getMessage());
            }
        }
    }
}
