package com.tilechat.chat;

import com.tilechat.common.config.CommonConfig;
import com.tilechat.common.config.RedisCacheConfig;
import com.tilechat.common.consumer.ConsumerConfig;
import com.tilechat.common.outbox.OutboxConfig;
import com.tilechat.common.storage.StorageConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@Import({CommonConfig.class, RedisCacheConfig.class, OutboxConfig.class, ConsumerConfig.class, StorageConfig.class})
@EntityScan({"com.tilechat.chat.domain", "com.tilechat.common.outbox", "com.tilechat.common.consumer"})
@EnableJpaRepositories({"com.tilechat.chat.repository", "com.tilechat.common.outbox", "com.tilechat.common.consumer"})
public class ChatServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
