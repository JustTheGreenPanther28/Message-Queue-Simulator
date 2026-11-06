package com.chat.notification.web;

import com.chat.notification.model.Notification;
import com.chat.notification.repository.NotificationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class NotificationController {

    private final NotificationRepository repository;

    public NotificationController(NotificationRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/notifications")
    public List<Notification> forUser(@RequestParam long userId,
                                      @RequestParam(defaultValue = "50") int limit) {
        return repository.findForRecipient(userId, Math.min(Math.max(limit, 1), 200));
    }
}
