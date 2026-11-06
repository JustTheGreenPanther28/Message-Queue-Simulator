package com.tilechat.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;

/**
 * In the {@code prod} profile, refuses to start if any local-development default is still in
 * use. Runs as soon as the configuration is loaded, before any bean is created or any database,
 * broker or cache is contacted. A misconfigured deployment should stop, not run with known
 * credentials. Registered in META-INF/spring.factories, so every service gets it.
 */
public class ProductionSafetyCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();
        if (env.acceptsProfiles(Profiles.of("prod"))) verify(env);
    }

    /** @throws IllegalStateException listing every problem found */
    public static void verify(Environment env) {
        List<String> problems = new ArrayList<>();
        String jwt = env.getProperty("app.jwt.secret", "");
        if (jwt.contains("local-dev-secret") || jwt.length() < 32) problems.add("JWT_SECRET is a dev default or shorter than 32 characters");
        if ("app".equals(env.getProperty("spring.datasource.password"))) problems.add("DB_PASSWORD is the dev default");
        if ("app".equals(env.getProperty("spring.rabbitmq.password"))) problems.add("RABBIT_PASSWORD is the dev default");
        if ("tilechat-local-secret".equals(env.getProperty("app.storage.secret-key"))) problems.add("S3_SECRET_KEY is the dev default");
        if (env.containsProperty("spring.data.redis.host") && env.getProperty("spring.data.redis.password", "").isBlank()) {
            problems.add("REDIS_PASSWORD is not set");
        }
        String origins = env.getProperty("app.websocket.allowed-origins", "*");
        if (origins.isBlank() || origins.contains("*")) problems.add("WS_ALLOWED_ORIGINS must list your web origin(s), not '*'");
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with an insecure production config: " + String.join("; ", problems));
        }
        log.info("Production configuration checks passed");
    }
}
