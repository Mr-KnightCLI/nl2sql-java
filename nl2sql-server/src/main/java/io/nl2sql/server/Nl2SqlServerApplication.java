package io.nl2sql.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * nl2sql REST server.
 *
 * <p>Configuration, highest priority first:
 * <ol>
 *   <li>command-line arguments ({@code --nl2sql.db.host=...})</li>
 *   <li>{@code NL2SQL_*} environment variables (same names as the Python version, see {@link EnvironmentMapping})</li>
 *   <li>the properties file named by {@code NL2SQL_CONFIG}, default {@code ./nl2sql.properties}</li>
 *   <li>built-in defaults</li>
 * </ol>
 */
@SpringBootApplication
@EnableConfigurationProperties(Nl2SqlProperties.class)
public class Nl2SqlServerApplication {

    public static void main(String[] args) {
        EnvironmentMapping.apply(System.getenv());
        SpringApplication.run(Nl2SqlServerApplication.class, args);
    }
}
