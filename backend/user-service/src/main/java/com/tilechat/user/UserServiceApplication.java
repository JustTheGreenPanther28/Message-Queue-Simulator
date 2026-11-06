package com.tilechat.user;

import com.tilechat.common.config.CommonConfig;
import com.tilechat.common.config.RedisCacheConfig;
import com.tilechat.common.outbox.OutboxConfig;
import com.tilechat.common.storage.StorageConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@Import({CommonConfig.class, RedisCacheConfig.class, OutboxConfig.class, StorageConfig.class})
@EntityScan({"com.tilechat.user.domain", "com.tilechat.common.outbox"})
@EnableJpaRepositories({"com.tilechat.user.repository", "com.tilechat.common.outbox"})
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
