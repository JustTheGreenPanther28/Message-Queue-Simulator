package com.tilechat.common.outbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Import into a service that publishes events. Also add this package to its JPA scanning. */
@Configuration
@EnableScheduling
@Import({OutboxWriter.class, OutboxRelay.class})
public class OutboxConfig {
}
