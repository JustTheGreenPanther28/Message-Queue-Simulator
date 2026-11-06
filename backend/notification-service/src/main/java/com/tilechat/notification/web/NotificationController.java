package com.tilechat.notification.web;

import com.tilechat.common.security.AuthUser;
import com.tilechat.notification.dto.NotificationPage;
import com.tilechat.notification.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public NotificationPage list(@AuthenticationPrincipal AuthUser me, @RequestParam(defaultValue = "30") int limit) {
        return notifications.list(me.id(), limit);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@AuthenticationPrincipal AuthUser me) {
        notifications.markAllRead(me.id());
    }

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readConversation(@AuthenticationPrincipal AuthUser me, @RequestParam Long conversationId) {
        notifications.markConversationRead(me.id(), conversationId);
    }
}
