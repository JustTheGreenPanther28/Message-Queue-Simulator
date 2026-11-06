package com.chat.message.web;

import com.chat.message.dto.SendMessageRequest;
import com.chat.message.dto.UpdateMessageRequest;
import com.chat.message.model.Message;
import com.chat.message.service.MessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Idempotency-Key (optional): a client-generated UUID per message. If the
     * client times out and retries with the same key, no duplicate is created —
     * the original message is returned with 200 instead of 201.
     */
    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<Message> send(@PathVariable long conversationId,
                                        @RequestHeader("X-User-Id") long userId,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        @Valid @RequestBody SendMessageRequest request) {
        MessageService.SendResult result =
                messageService.send(conversationId, userId, request.content(), idempotencyKey);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .header("Idempotent-Replayed", String.valueOf(!result.created()))
                .body(result.message());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<Message> list(@PathVariable long conversationId,
                              @RequestHeader("X-User-Id") long userId,
                              @RequestParam(required = false) Long beforeSeq,
                              @RequestParam(defaultValue = "" + MessageService.DEFAULT_PAGE_SIZE)
                              @Min(1) @Max(200) int limit) {
        return messageService.list(conversationId, userId, beforeSeq, limit);
    }

    @PatchMapping("/messages/{messageId}")
    public Message edit(@PathVariable long messageId,
                        @RequestHeader("X-User-Id") long userId,
                        @Valid @RequestBody UpdateMessageRequest request) {
        return messageService.edit(messageId, userId, request.content(), request.version());
    }

    @DeleteMapping("/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long messageId, @RequestHeader("X-User-Id") long userId) {
        messageService.delete(messageId, userId);
    }
}
