package com.tilechat.chat.config;

import com.tilechat.common.events.EventTopics;
import com.tilechat.common.messaging.RabbitCommonConfig;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String USER_EVENTS_QUEUE = "chat-service.user-events";

    @Bean
    public Declarables userEventsQueue() {
        return RabbitCommonConfig.queueWithDlq(USER_EVENTS_QUEUE, EventTopics.USER_REGISTERED, EventTopics.USER_UPDATED);
    }
}
