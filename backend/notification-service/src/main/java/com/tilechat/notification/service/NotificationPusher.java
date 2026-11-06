package com.tilechat.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Live push after commit. Offline users see it next time they open the bell. */
@Component
public class NotificationPusher {

    private static final Logger log = LoggerFactory.getLogger(NotificationPusher.class);

    private final SimpMessagingTemplate ws;

    public NotificationPusher(SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    @TransactionalEventListener
    public void on(NotificationService.Created e) {
        try {
            ws.convertAndSendToUser(String.valueOf(e.userId()), "/queue/notifications", e.notification());
        } catch (Exception ex) {
            log.warn("Push to user {} failed: {}", e.userId(), ex.getMessage());
        }
    }
}
