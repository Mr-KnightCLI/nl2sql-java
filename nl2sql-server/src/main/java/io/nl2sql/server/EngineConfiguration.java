package io.nl2sql.server;

import io.nl2sql.core.Nl2SqlEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;

@Configuration(proxyBeanMethods = false)
class EngineConfiguration {
    private static final Logger log = LoggerFactory.getLogger(EngineConfiguration.class);

    @Bean(destroyMethod = "close")
    Nl2SqlEngine nl2SqlEngine(Nl2SqlProperties props, @Value("${server.address:}") String address) {
        log.info("nl2sql: database {} / LLM {}", props.getDb(), props.getLlm());
        String key = props.getServer().getApiKey();
        if ((key == null || key.isBlank()) && !isLoopback(address)) {
            log.warn("nl2sql: listening on '{}' WITHOUT an API key - anyone who can reach this port can query the "
                    + "database. Set NL2SQL_SERVER_API_KEY (nl2sql.server.api-key).", address.isBlank() ? "all interfaces" : address);
        }
        return Nl2SqlEngine.create(props);
    }

    private static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) return false;
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
