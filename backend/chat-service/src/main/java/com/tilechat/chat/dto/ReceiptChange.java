package com.tilechat.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tilechat.chat.domain.MessageStatus;

/** A message's aggregate status moved forward. Pushed to the sender on /user/queue/receipts. */
public record ReceiptChange(@JsonIgnore Long senderId, Long conversationId, Long messageId, MessageStatus status) {
}
