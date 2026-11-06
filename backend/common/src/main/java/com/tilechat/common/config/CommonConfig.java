package com.tilechat.common.config;

import com.tilechat.common.messaging.RabbitCommonConfig;
import com.tilechat.common.security.JwtConfig;
import com.tilechat.common.web.GlobalExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Everything every service needs: JWT, JSON error handling, the RabbitMQ basics. */
@Configuration
@Import({JwtConfig.class, GlobalExceptionHandler.class, RabbitCommonConfig.class})
public class CommonConfig {
}
