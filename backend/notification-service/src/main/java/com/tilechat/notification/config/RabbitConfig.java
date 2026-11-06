package com.tilechat.notification.config;

import com.tilechat.common.events.EventTopics;
import com.tilechat.common.messaging.RabbitCommonConfig;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String MESSAGE_QUEUE = "notification-service.message-created";
    public static final String MEMBERS_QUEUE = "notification-service.members-added";

    @Bean
    public Declarables messageCreatedQueue() {
        return RabbitCommonConfig.queueWithDlq(MESSAGE_QUEUE, EventTopics.MESSAGE_CREATED);
    }

    @Bean
    public Declarables membersAddedQueue() {
        return RabbitCommonConfig.queueWithDlq(MEMBERS_QUEUE, EventTopics.MEMBERS_ADDED);
    }
}
