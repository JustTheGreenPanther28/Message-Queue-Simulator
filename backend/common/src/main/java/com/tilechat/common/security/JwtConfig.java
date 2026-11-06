package com.tilechat.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class JwtConfig {

    @Bean
    public JwtService jwtService(@Value("${app.jwt.secret}") String secret,
                                 @Value("${app.jwt.ttl:24h}") Duration ttl) {
        return new JwtService(secret, ttl);
    }

    @Bean
    public StompAuthInterceptor stompAuthInterceptor(JwtService jwtService) {
        return new StompAuthInterceptor(jwtService);
    }
}
