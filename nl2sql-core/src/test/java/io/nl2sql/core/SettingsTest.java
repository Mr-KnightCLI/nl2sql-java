package io.nl2sql.core;

import io.nl2sql.core.config.DatabaseSettings;
import io.nl2sql.core.config.Dialect;
import io.nl2sql.core.config.LlmSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsTest {

    private static DatabaseSettings db(String type, String host, Integer port, String name, String options) {
        DatabaseSettings d = new DatabaseSettings();
        d.setType(type);
        d.setHost(host);
        d.setPort(port);
        d.setName(name);
        d.setOptions(options);
        return d;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "mysql      | h | null  | sales   | null            | jdbc:mysql://h:3306/sales                          | MYSQL",
            "MariaDB    | h | null  | sales   | null            | jdbc:mariadb://h:3306/sales                        | MARIADB",
            "postgres   | h | null  | sales   | sslmode=require | jdbc:postgresql://h:5432/sales?sslmode=require     | POSTGRES",
            "SQL Server | h | 14330 | sales   | null            | jdbc:sqlserver://h:14330;databaseName=sales        | SQLSERVER",
            "sqlserver  | h | null  | sales   | encrypt=false   | jdbc:sqlserver://h:1433;databaseName=sales;encrypt=false | SQLSERVER",
            "oracle     | h | null  | XEPDB1  | null            | jdbc:oracle:thin:@//h:1521/XEPDB1                  | ORACLE",
            "sqlite     | null | null | /d/a.db | null          | jdbc:sqlite:/d/a.db                                | SQLITE"})
    void buildsJdbcUrls(String type, String host, Integer port, String name, String options, String url, Dialect dialect) {
        DatabaseSettings d = db(type, host, port, name, options);
        assertThat(d.jdbcUrl()).isEqualTo(url);
        assertThat(d.dialect()).isEqualTo(dialect);
    }

    @Test
    void sqlServerNamedInstanceHasNoPort() {
        assertThat(db("sqlserver", "srv01\\SQLEXPRESS", null, "sales", null).jdbcUrl())
                .isEqualTo("jdbc:sqlserver://srv01\\SQLEXPRESS;databaseName=sales");
    }

    @Test
    void fullUrlWins() {
        DatabaseSettings d = db("oracle", "h", null, "x", null);
        d.setUrl("jdbc:postgresql://db/erp");
        assertThat(d.jdbcUrl()).isEqualTo("jdbc:postgresql://db/erp");
        assertThat(d.dialect()).isEqualTo(Dialect.POSTGRES);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "mysql+pymysql://localhost:3306/capstone                 | jdbc:mysql://localhost:3306/capstone            | null | null",
            "mysql+pymysql://app:p%40ss@db:3307/shop?charset=utf8mb4 | jdbc:mysql://db:3307/shop                       | app  | p@ss",
            "postgresql+psycopg://u:pw@h/erp?sslmode=require          | jdbc:postgresql://h:5432/erp?sslmode=require    | u    | pw",
            "mssql+pyodbc://sa:x@h:1433/sales?driver=ODBC+Driver+18+for+SQL+Server&TrustServerCertificate=yes | jdbc:sqlserver://h:1433;databaseName=sales;trustServerCertificate=true | sa | x",
            "oracle+oracledb://scott:t@h:1521/?service_name=FREEPDB1  | jdbc:oracle:thin:@//h:1521/FREEPDB1             | scott | t",
            "sqlite:///data/tms.db                                    | jdbc:sqlite:data/tms.db                         | null | null",
            "sqlite:///C:/data/tms.db                                 | jdbc:sqlite:C:/data/tms.db                      | null | null"})
    void acceptsPythonStyleUrls(String raw, String jdbc, String user, String password) {
        DatabaseSettings d = new DatabaseSettings();
        d.setUrl(raw);
        assertThat(d.jdbcUrl()).isEqualTo(jdbc);
        assertThat(d.getUsername()).isEqualTo(user);
        assertThat(d.getPassword()).isEqualTo(password);
        d.setUsername("explicit");
        assertThat(d.getUsername()).isEqualTo("explicit");
    }

    @Test
    void helpfulErrors() {
        assertThatThrownBy(() -> new DatabaseSettings().jdbcUrl()).hasMessageContaining("NL2SQL_DB_TYPE");
        assertThatThrownBy(() -> db("db2", "h", null, "d", null).jdbcUrl()).hasMessageContaining("Unknown database type");
        assertThatThrownBy(() -> db("mysql", null, null, "d", null).jdbcUrl()).hasMessageContaining("host");
        assertThatThrownBy(() -> db("oracle", "h", null, null, null).jdbcUrl()).hasMessageContaining("service name");
    }

    @Test
    void secretsNotPrinted() {
        DatabaseSettings d = db("mysql", "h", null, "d", null);
        d.setPassword("s3cret");
        LlmSettings l = new LlmSettings();
        l.setApiKey("sk-abc123");
        assertThat(d.toString()).doesNotContain("s3cret");
        assertThat(l.toString()).doesNotContain("sk-abc123");
    }

    @Test
    void headersAndLists() {
        LlmSettings l = new LlmSettings();
        l.setHeaders("X-Gateway-Key: abc; X-Team: data");
        assertThat(l.headerMap()).containsEntry("X-Gateway-Key", "abc").containsEntry("X-Team", "data");
        DatabaseSettings d = new DatabaseSettings();
        d.setIncludeTables(List.of(" orders ", "", "items"));
        assertThat(d.getIncludeTables()).containsExactly("orders", "items");
    }
}
