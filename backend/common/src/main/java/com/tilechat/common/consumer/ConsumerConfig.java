package com.tilechat.common.consumer;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Import into a service that consumes events. Also add this package to its JPA scanning. */
@Configuration
@EnableScheduling
@Import(IdempotentConsumer.class)
public class ConsumerConfig {
}
