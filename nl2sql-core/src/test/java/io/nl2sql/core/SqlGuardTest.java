package io.nl2sql.core;

import io.nl2sql.core.config.Dialect;
import io.nl2sql.core.config.GuardrailSettings;
import io.nl2sql.core.guard.SqlGuard;
import io.nl2sql.core.guard.ValidatedSql;
import io.nl2sql.core.schema.Column;
import io.nl2sql.core.schema.ForeignKey;
import io.nl2sql.core.schema.SchemaCatalog;
import io.nl2sql.core.schema.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

    static SchemaCatalog catalog(Dialect d, String schema) {
        SchemaCatalog c = new SchemaCatalog(d, schema);
        c.add(new Table("orders", "orders", new ArrayList<>(List.of(
                new Column("id", "id", "INT", false, true, ""),
                new Column("cust_id", "cust_id", "INT", false, false, ""),
                new Column("amount", "amount", "DECIMAL", true, false, ""),
                new Column("status", "status", "VARCHAR", true, false, ""),
                new Column("created_at", "created_at", "TIMESTAMP", true, false, ""))),
                List.of(new ForeignKey(List.of("cust_id"), "customers", List.of("id"))), ""));
        c.add(new Table("customers", "customers", new ArrayList<>(List.of(
                new Column("id", "id", "INT", false, true, ""),
                new Column("name", "name", "VARCHAR", true, false, ""),
                new Column("email", "email", "VARCHAR", true, false, ""))), List.of(), ""));
        return c;
    }

    static SqlGuard guard(Dialect d) {
        return new SqlGuard(catalog(d, null), new GuardrailSettings());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM orders",
            "UPDATE orders SET status = 'X'",
            "INSERT INTO orders (id) VALUES (1)",
            "DROP TABLE orders",
            "CREATE TABLE x (a int)",
            "TRUNCATE TABLE orders",
            "SELECT 1 FROM orders; DELETE FROM orders",
            "SELECT * FROM orders; SELECT 1",
            "WITH x AS (SELECT 1) DELETE FROM orders",
            "SELECT * INTO backup FROM orders",
            "SELECT id FROM orders FOR UPDATE",
            "SELECT id FROM orders INTO OUTFILE '/tmp/x'",
            "SELECT pg_sleep(10) FROM orders",
            "SELECT id FROM orders WHERE SLEEP(5) = 0",
            "SELECT id FROM users",
            "SELECT o.missing FROM orders o",
            "SELECT nothing_here FROM orders",
            "SELECT x.id FROM orders o",
            "SELECT 1"})
    void rejects(String sql) {
        assertThatThrownBy(() -> guard(Dialect.POSTGRES).validate(sql)).isInstanceOf(GuardrailViolation.class);
    }

    @Test
    void stringsDoNotTriggerKeywordChecks() {
        ValidatedSql v = guard(Dialect.POSTGRES).validate("SELECT id FROM orders WHERE status = 'insert into; for update -- drop'");
        assertThat(v.tables()).containsExactly("orders");
    }

    @Test
    void acceptsJoinsCtesAliasesAndSubqueries() {
        SqlGuard g = guard(Dialect.POSTGRES);
        assertThat(g.validate("""
                WITH big AS (SELECT cust_id, amount FROM orders WHERE amount > 100)
                SELECT c.name, SUM(b.amount) AS total, COUNT(*) AS n
                FROM big b JOIN customers c ON c.id = b.cust_id
                GROUP BY c.name ORDER BY total DESC""").tables()).containsExactlyInAnyOrder("orders", "customers");
        assertThat(g.validate("SELECT t.cust_id, t.cnt FROM (SELECT cust_id, COUNT(*) AS cnt FROM orders GROUP BY cust_id) t ORDER BY t.cnt DESC")
                .tables()).containsExactly("orders");
        g.validate("SELECT status, COUNT(*) n FROM orders GROUP BY status ORDER BY n DESC");
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void dialectSpecificSqlParses(Dialect d) {
        String sql = switch (d) {
            case MYSQL, MARIADB -> "SELECT `status`, COUNT(*) AS n FROM orders WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 90 DAY) GROUP BY `status` LIMIT 10";
            case SQLSERVER -> "SELECT TOP 10 [status], COUNT(*) AS n FROM orders WHERE created_at >= DATEADD(day, -90, GETDATE()) GROUP BY [status]";
            case ORACLE -> "SELECT status, COUNT(*) AS n FROM orders WHERE created_at >= SYSDATE - 90 GROUP BY status FETCH FIRST 10 ROWS ONLY";
            case POSTGRES -> "SELECT status, COUNT(*) AS n FROM orders WHERE created_at >= now() - INTERVAL '90 days' GROUP BY status LIMIT 10";
            case SQLITE -> "SELECT status, COUNT(*) AS n FROM orders WHERE created_at >= date('now', '-90 day') GROUP BY status LIMIT 10";
        };
        assertThat(guard(d).validate(sql).tables()).containsExactly("orders");
    }

    @Test
    void blockedColumns() {
        GuardrailSettings s = new GuardrailSettings();
        s.setBlockedColumns(List.of("customers.email"));
        SqlGuard g = new SqlGuard(catalog(Dialect.MYSQL, null), s);
        assertThatThrownBy(() -> g.validate("SELECT email FROM customers")).hasMessageContaining("restricted");
        assertThatThrownBy(() -> g.validate("SELECT c.email FROM customers c")).hasMessageContaining("restricted");
        assertThatThrownBy(() -> g.validate("SELECT * FROM customers")).hasMessageContaining("restricted");
        g.validate("SELECT COUNT(*) FROM customers");
    }

    @ParameterizedTest
    @EnumSource(value = Dialect.class, names = {"POSTGRES", "SQLSERVER", "ORACLE"})
    void qualifiesTablesWithConfiguredSchema(Dialect d) {
        SqlGuard g = new SqlGuard(catalog(d, "sales"), new GuardrailSettings());
        String out = g.validate("WITH big AS (SELECT cust_id FROM orders) SELECT c.name FROM big b JOIN customers c ON c.id = b.cust_id").sql();
        assertThat(out).contains("sales.orders").contains("sales.customers").doesNotContain("sales.big");
        assertThatThrownBy(() -> g.validate("SELECT id FROM hr.orders")).hasMessageContaining("not exposed");
    }

    @Test
    void mysqlNeverQualified() {
        SqlGuard g = new SqlGuard(catalog(Dialect.MYSQL, "sales"), new GuardrailSettings());
        assertThat(g.validate("SELECT id FROM orders").sql()).isEqualTo("SELECT id FROM orders");
    }
}
