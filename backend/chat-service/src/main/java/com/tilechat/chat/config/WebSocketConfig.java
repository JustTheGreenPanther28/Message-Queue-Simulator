package com.tilechat.chat.config;

import com.tilechat.common.config.JwtStompConfigurer;
import com.tilechat.common.security.StompAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig extends JwtStompConfigurer {

    public WebSocketConfig(StompAuthInterceptor interceptor) {
        super(interceptor);
    }

    @Override
    protected String endpoint() {
        return "/ws";
    }
}
