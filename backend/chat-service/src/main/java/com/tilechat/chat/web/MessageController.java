package com.tilechat.chat.web;

import com.tilechat.chat.dto.Requests;
import com.tilechat.chat.service.ReceiptService;
import com.tilechat.common.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final ReceiptService receipts;

    public MessageController(ReceiptService receipts) {
        this.receipts = receipts;
    }

    /** Delivery acknowledgement from the recipient's device. Safe to repeat. */
    @PostMapping("/delivered")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delivered(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody(required = false) Requests.Delivered req) {
        receipts.markDelivered(me.id(), req == null ? null : req.messageIds());
    }
}
