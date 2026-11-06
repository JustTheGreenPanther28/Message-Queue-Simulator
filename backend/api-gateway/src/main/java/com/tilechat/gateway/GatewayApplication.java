package com.tilechat.gateway;

import org.springframework.boot.SpringApplication;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;


@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    /**
     * Resolve service hostnames with the JDK resolver (short TTL, see JAVA_OPTS / application.yml)
     * instead of Netty's DNS cache, which keeps Docker DNS answers for 10 minutes. Without this,
     * a redeployed service that comes back on a new IP is unreachable through the gateway.
     */
    @Bean
    public HttpClientCustomizer jdkDnsResolver() {
        return client -> client.resolver(DefaultAddressResolverGroup.INSTANCE);
    }
}
