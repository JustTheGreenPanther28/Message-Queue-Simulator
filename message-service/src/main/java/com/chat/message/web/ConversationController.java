package com.chat.message.web;

import com.chat.message.dto.CreateConversationRequest;
import com.chat.message.model.Conversation;
import com.chat.message.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth is out of scope: the caller's id comes from the X-User-Id header.
 * In production this would come from a validated JWT at the API gateway.
 */
@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@RequestHeader("X-User-Id") long userId,
                               @Valid @RequestBody CreateConversationRequest request) {
        return conversationService.create(userId, request);
    }

    @GetMapping("/{id}")
    public Conversation get(@PathVariable long id, @RequestHeader("X-User-Id") long userId) {
        return conversationService.get(id, userId);
    }
}
