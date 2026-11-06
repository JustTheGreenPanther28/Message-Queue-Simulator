package com.tilechat.chat.realtime;

import com.tilechat.chat.dto.ConversationDto;
import com.tilechat.chat.dto.MemberDto;
import com.tilechat.chat.dto.MessageDto;
import com.tilechat.chat.dto.ReceiptChange;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * In-process domain events. Services publish them inside their transaction; the
 * dispatcher pushes them over WebSocket only after the commit succeeds, so clients
 * never see data that was rolled back.
 */
public final class RealtimeEvents {
    private RealtimeEvents() {}

    public record MessagePosted(MessageDto message, Collection<Long> audience) {}

    public record ReceiptsChanged(List<ReceiptChange> changes) {}

    /** payloads: what each user should see (a direct chat can look different to each side). */
    public record ConversationChanged(Long conversationId, Map<Long, ConversationDto> payloads, Collection<Long> removedUserIds) {}

    public record ProfileChanged(MemberDto profile, Collection<Long> audience) {}
}
