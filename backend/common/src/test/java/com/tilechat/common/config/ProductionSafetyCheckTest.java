package com.tilechat.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

class ProductionSafetyCheckTest {

    private MockEnvironment safe() {
        return new MockEnvironment()
                .withProperty("app.jwt.secret", "x".repeat(48))
                .withProperty("spring.datasource.password", "db-strong")
                .withProperty("spring.rabbitmq.password", "mq-strong")
                .withProperty("spring.data.redis.host", "redis")
                .withProperty("spring.data.redis.password", "redis-strong")
                .withProperty("app.websocket.allowed-origins", "https://chat.example.com");
    }

    @Test
    void acceptsAProperConfig() {
        assertThatCode(() -> ProductionSafetyCheck.verify(safe())).doesNotThrowAnyException();
    }

    @Test
    void refusesDevDefaultsAndListsEveryProblem() {
        MockEnvironment env = safe()
                .withProperty("app.jwt.secret", "local-dev-secret-change-me-please-0123456789")
                .withProperty("spring.datasource.password", "app")
                .withProperty("spring.data.redis.password", "")
                .withProperty("app.websocket.allowed-origins", "*");
        assertThatThrownBy(() -> ProductionSafetyCheck.verify(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET").hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("REDIS_PASSWORD").hasMessageContaining("WS_ALLOWED_ORIGINS");
    }
}
