package com.tilechat.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tilechat.common.events.EventTopics;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class RabbitCommonConfig {

    @Bean
    public TopicExchange eventsExchange() {
        return ExchangeBuilder.topicExchange(EventTopics.EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(EventTopics.DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    /** Listeners convert JSON to the method's parameter type (no Java class names on the wire). */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper mapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(mapper);
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    /**
     * A durable queue bound to the events exchange, plus its dead-letter queue. A message
     * that still fails after the listener's retries is rejected and lands in "<name>.dlq".
     */
    public static Declarables queueWithDlq(String name, String... routingKeys) {
        Queue dlq = QueueBuilder.durable(name + ".dlq").build();
        Queue queue = QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", EventTopics.DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", name + ".dlq")
                .build();
        List<Declarable> all = new ArrayList<>(List.of(dlq, queue,
                new Binding(dlq.getName(), Binding.DestinationType.QUEUE, EventTopics.DEAD_LETTER_EXCHANGE, name + ".dlq", null)));
        for (String key : routingKeys) {
            all.add(new Binding(name, Binding.DestinationType.QUEUE, EventTopics.EXCHANGE, key, null));
        }
        return new Declarables(all);
    }
}
