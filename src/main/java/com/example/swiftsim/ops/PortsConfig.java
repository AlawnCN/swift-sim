package com.example.swiftsim.ops;

import lombok.RequiredArgsConstructor;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Extra Tomcat connectors: admin console and AI API, each bound to 127.0.0.1 only. Routing per port: {@link PortGuardFilter}. */
@Configuration
@RequiredArgsConstructor
public class PortsConfig {
    private final OpsProps props;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> extraConnectors() {
        return f -> {
            if (props.isAdminEnabled()) f.addAdditionalTomcatConnectors(connector(props.getAdminPort(), props.getAdminAddress()));
            if (props.isAiEnabled()) f.addAdditionalTomcatConnectors(connector(props.getAiPort(), props.getAiAddress()));
        };
    }

    private static Connector connector(int port, String address) {
        Connector c = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        c.setPort(port);
        c.setProperty("address", address);
        return c;
    }
}
