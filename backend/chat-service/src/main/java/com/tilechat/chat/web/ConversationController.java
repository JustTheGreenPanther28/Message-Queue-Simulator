package com.tilechat.chat.web;

import com.tilechat.chat.dto.ConversationDto;
import com.tilechat.chat.dto.MessageDto;
import com.tilechat.chat.dto.Requests;
import com.tilechat.chat.service.ConversationService;
import com.tilechat.chat.service.ConversationViews;
import com.tilechat.chat.service.MembershipGuard;
import com.tilechat.chat.service.MessageService;
import com.tilechat.chat.service.ReceiptService;
import com.tilechat.common.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.tilechat.common.storage.PresignedUpload;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversations;
    private final ConversationViews views;
    private final MessageService messages;
    private final ReceiptService receipts;
    private final MembershipGuard guard;

    public ConversationController(ConversationService conversations, ConversationViews views, MessageService messages,
                                  ReceiptService receipts, MembershipGuard guard) {
        this.conversations = conversations;
        this.views = views;
        this.messages = messages;
        this.receipts = receipts;
        this.guard = guard;
    }

    @GetMapping
    public List<ConversationDto> list(@AuthenticationPrincipal AuthUser me) {
        return views.listFor(me.id());
    }

    @GetMapping("/{id}")
    public ConversationDto get(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        guard.requireVisible(id, me.id());
        return views.view(id, me.id());
    }

    @PostMapping("/direct")
    public ConversationDto openDirect(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody Requests.OpenDirect req) {
        return conversations.openDirect(me, req.userId());
    }

    @PostMapping("/group")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDto createGroup(@AuthenticationPrincipal AuthUser me,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                       @Valid @RequestBody Requests.CreateGroup req) {
        return conversations.createGroup(me, req, idempotencyKey);
    }

    @PostMapping("/{id}/members")
    public ConversationDto addMembers(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                      @Valid @RequestBody Requests.AddMembers req) {
        return conversations.addMembers(me, id, req);
    }

    @DeleteMapping("/{id}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        conversations.leave(me, id);
    }

    /** Accept a message request or group invite. Safe to repeat. */
    @PostMapping("/{id}/accept")
    public ConversationDto accept(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return conversations.accept(me, id);
    }

    /** Decline a message request or group invite. Safe to repeat. */
    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        conversations.reject(me, id);
    }

    /** Delete the chat for you (direct: hide + clear your history; group: leave). Safe to repeat. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        conversations.deleteChat(me, id);
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> history(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                    @RequestParam(required = false) Long beforeSeq,
                                    @RequestParam(required = false) Long afterSeq,
                                    @RequestParam(required = false) Integer limit) {
        return messages.history(me, id, beforeSeq, afterSeq, limit);
    }

    /** Text, or an image already uploaded via {@code /attachments/upload-url}. Idempotent per clientMessageId. */
    @PostMapping("/{id}/messages")
    public MessageDto send(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                           @Valid @RequestBody Requests.SendMessage req) {
        return messages.send(me, id, req);
    }

    /** Presigned URL for uploading an image straight to object storage. */
    @PostMapping("/{id}/attachments/upload-url")
    public PresignedUpload uploadUrl(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                     @Valid @RequestBody Requests.UploadUrl req) {
        return messages.uploadUrl(me, id, req.contentType(), req.size());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        receipts.markRead(me.id(), id);
    }
}
