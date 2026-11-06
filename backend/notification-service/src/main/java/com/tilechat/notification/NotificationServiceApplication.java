package com.tilechat.notification;

import com.tilechat.common.config.CommonConfig;
import com.tilechat.common.consumer.ConsumerConfig;
import com.tilechat.common.storage.StorageConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@Import({CommonConfig.class, ConsumerConfig.class, StorageConfig.class})
@EntityScan({"com.tilechat.notification.domain", "com.tilechat.common.consumer"})
@EnableJpaRepositories({"com.tilechat.notification.repository", "com.tilechat.common.consumer"})
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
