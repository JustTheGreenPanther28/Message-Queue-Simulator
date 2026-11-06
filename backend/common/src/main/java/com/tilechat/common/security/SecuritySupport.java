package com.tilechat.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tilechat.common.web.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Builds the stateless JWT filter chain every service uses; only the public paths differ. */
public final class SecuritySupport {

    private SecuritySupport() {}

    public static SecurityFilterChain statelessJwtChain(HttpSecurity http, JwtService jwtService,
                                                        ObjectMapper mapper, String... publicPaths) throws Exception {
        http.csrf(c -> c.disable())
                .cors(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(publicPaths).permitAll()
                        .requestMatchers("/error", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    mapper.writeValue(res.getOutputStream(),
                            ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Authentication required"));
                }))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
