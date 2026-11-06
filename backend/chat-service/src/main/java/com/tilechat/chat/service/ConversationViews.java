package com.tilechat.chat.service;

import com.tilechat.chat.domain.Conversation;
import com.tilechat.chat.domain.ConversationMember;
import com.tilechat.chat.domain.MemberStatus;
import com.tilechat.chat.domain.Message;
import com.tilechat.chat.dto.ConversationDto;
import com.tilechat.chat.dto.MemberDto;
import com.tilechat.chat.dto.MessageDto;
import com.tilechat.chat.repository.ConversationMemberRepository;
import com.tilechat.chat.repository.ConversationRepository;
import com.tilechat.chat.repository.MessageReceiptRepository;
import com.tilechat.chat.repository.MessageRepository;
import com.tilechat.common.web.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read side: conversation views as one particular user sees them, built with a fixed number
 * of queries (no N+1). Per-viewer because of silent blocking: if the last message came from
 * someone the viewer blocked, the viewer's preview shows the last message they can see.
 */
@Service
public class ConversationViews {

    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    private final MessageReceiptRepository receipts;
    private final UserDirectory directory;
    private final DtoMapper mapper;

    public ConversationViews(ConversationRepository conversations, ConversationMemberRepository members,
                             MessageRepository messages, MessageReceiptRepository receipts, UserDirectory directory,
                             DtoMapper mapper) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.receipts = receipts;
        this.directory = directory;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> listFor(Long userId) {
        return build(conversations.findAllForUser(userId), userId, true).stream()
                .sorted(Comparator.comparing(ConversationDto::lastActivityAt).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDto view(Long conversationId, Long viewerId) {
        return build(List.of(load(conversationId)), viewerId, true).get(0);
    }

    /**
     * What to push to each user after a change. unreadCount is left out (clients keep their own).
     * Groups look the same to everyone; direct chats are built per viewer.
     */
    @Transactional(readOnly = true)
    public Map<Long, ConversationDto> payloads(Long conversationId, Collection<Long> audience) {
        Conversation c = load(conversationId);
        Map<Long, ConversationDto> result = new LinkedHashMap<>();
        if (c.isGroup()) {
            ConversationDto shared = build(List.of(c), null, false).get(0);
            audience.forEach(uid -> result.put(uid, shared));
        } else {
            audience.forEach(uid -> result.put(uid, build(List.of(c), uid, false).get(0)));
        }
        return result;
    }

    private Conversation load(Long id) {
        return conversations.findById(id).orElseThrow(() -> ApiException.notFound("Conversation not found"));
    }

    private List<ConversationDto> build(List<Conversation> convs, Long viewerId, boolean withUnread) {
        if (convs.isEmpty()) return List.of();
        List<Long> ids = convs.stream().map(Conversation::getId).toList();

        Map<Long, List<ConversationMember>> rows = members.findByConversationIdIn(ids).stream()
                .sorted(Comparator.comparing(ConversationMember::getConversationId))
                .collect(Collectors.groupingBy(ConversationMember::getConversationId, LinkedHashMap::new, Collectors.toList()));
        Set<Long> allUsers = rows.values().stream().flatMap(List::stream).map(ConversationMember::getUserId).collect(Collectors.toSet());
        Map<Long, MemberDto> profiles = directory.members(allUsers);

        List<Long> lastIds = convs.stream().map(Conversation::getLastMessageId).filter(Objects::nonNull).toList();
        Map<Long, Message> lastMessages = messages.findAllById(lastIds).stream().collect(Collectors.toMap(Message::getId, m -> m));
        Set<Long> hiddenForViewer = viewerId == null || lastIds.isEmpty() ? Set.of() : new HashSet<>(receipts.findSuppressedAmong(viewerId, lastIds));

        Map<Long, Long> unread = new HashMap<>();
        if (viewerId != null && withUnread) {
            for (Object[] row : receipts.unreadCounts(viewerId)) unread.put((Long) row[0], ((Number) row[1]).longValue());
        }

        List<ConversationDto> result = new ArrayList<>();
        for (Conversation c : convs) {
            Message last = c.getLastMessageId() == null ? null : lastMessages.get(c.getLastMessageId());
            Instant activity = c.getLastActivityAt();
            long clearedSeq = viewerId == null ? 0 : rows.getOrDefault(c.getId(), List.of()).stream()
                    .filter(m -> m.getUserId().equals(viewerId)).mapToLong(ConversationMember::getClearedSeq).findFirst().orElse(0);
            if (last != null && (hiddenForViewer.contains(last.getId()) || last.getSeq() <= clearedSeq)) {
                last = messages.latestVisible(c.getId(), viewerId, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
                activity = last != null ? last.getCreatedAt() : c.getLastActivityAt();
            }
            List<MemberDto> people = rows.getOrDefault(c.getId(), List.of()).stream()
                    // Declined or silently hidden requests look like "pending" to everyone else.
                    .map(m -> profiles.get(m.getUserId()).withStatus(m.getStatus() == MemberStatus.ACCEPTED ? "ACCEPTED" : "PENDING"))
                    .toList();
            MessageDto lastDto = last == null ? null : mapper.message(last);
            result.add(new ConversationDto(c.getId(), c.getType(), c.getName(), c.getCreatedBy(), people, lastDto,
                    withUnread && viewerId != null ? unread.getOrDefault(c.getId(), 0L) : null, activity));
        }
        return result;
    }
}
