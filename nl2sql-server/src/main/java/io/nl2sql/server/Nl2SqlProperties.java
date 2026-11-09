package io.nl2sql.server;

import io.nl2sql.core.config.Nl2SqlSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds every {@code nl2sql.*} property onto the core settings, plus server-only options. */
@ConfigurationProperties(prefix = "nl2sql")
public class Nl2SqlProperties extends Nl2SqlSettings {
    private Server server = new Server();

    public Server getServer() {
        return server;
    }

    public void setServer(Server server) {
        this.server = server;
    }

    public static class Server {
        /** Clients must send this value in the X-API-Key header. Empty means no key check. */
        private String apiKey;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
