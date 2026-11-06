package com.tilechat.user.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tilechat.common.security.JwtService;
import com.tilechat.common.security.SecuritySupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt, ObjectMapper mapper) throws Exception {
        return SecuritySupport.statelessJwtChain(http, jwt, mapper, "/api/auth/**");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
