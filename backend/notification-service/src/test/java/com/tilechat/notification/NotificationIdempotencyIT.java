package com.tilechat.notification;

import com.tilechat.common.events.MessageCreatedEvent;
import com.tilechat.notification.messaging.ChatEventsListener;
import com.tilechat.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class NotificationIdempotencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired ChatEventsListener listener;
    @Autowired NotificationService notifications;

    private MessageCreatedEvent event(long messageId, List<Long> recipients) {
        return new MessageCreatedEvent(UUID.randomUUID().toString(), messageId, 1L, "GROUP", "Team", 9L, "Alice",
                null, "hello", recipients, List.of(), Instant.now());
    }

    @Test
    void redeliveredEventCreatesNoDuplicates() {
        MessageCreatedEvent e = event(100L, List.of(501L, 502L));
        listener.onMessageCreated(e);
        listener.onMessageCreated(e);
        listener.onMessageCreated(e);

        assertThat(notifications.list(501L, 50).items()).hasSize(1);
        assertThat(notifications.list(502L, 50).items()).hasSize(1);
        assertThat(notifications.list(501L, 50).items().get(0).title()).isEqualTo("Team");
        assertThat(notifications.list(501L, 50).items().get(0).body()).isEqualTo("Alice: hello");
    }

    @Test
    void concurrentDuplicateDeliveriesStillYieldOneNotification() throws Exception {
        MessageCreatedEvent e = event(200L, List.of(601L));
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                try { listener.onMessageCreated(e); } catch (Exception ignored) {
                    // the loser of the race fails on the primary key; in production RabbitMQ retries it and it's skipped
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        listener.onMessageCreated(e); // the "retry" after the race
        assertThat(notifications.list(601L, 50).items()).hasSize(1);
        assertThat(notifications.list(601L, 50).unreadCount()).isEqualTo(1);
    }

    @Test
    void messagesFromUnacceptedChatsAreRequests() {
        MessageCreatedEvent e = new MessageCreatedEvent(UUID.randomUUID().toString(), 400L, 2L, "DIRECT", null, 9L, "Alice",
                null, "hi there", List.of(801L), List.of(801L), Instant.now());
        listener.onMessageCreated(e);
        var n = notifications.list(801L, 10).items().get(0);
        assertThat(n.type().name()).isEqualTo("MESSAGE_REQUEST");
        assertThat(n.title()).isEqualTo("Alice wants to message you");
    }

    @Test
    void openingAConversationMarksItsNotificationsRead() {
        listener.onMessageCreated(event(300L, List.of(701L)));
        listener.onMessageCreated(event(301L, List.of(701L)));
        notifications.markConversationRead(701L, 1L);
        assertThat(notifications.list(701L, 50).unreadCount()).isZero();
    }
}
