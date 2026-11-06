package com.chat.message.service;

import com.chat.message.dto.CreateConversationRequest;
import com.chat.message.exception.ForbiddenException;
import com.chat.message.exception.NotFoundException;
import com.chat.message.model.Conversation;
import com.chat.message.repository.ConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ConversationService {

    private final ConversationRepository conversations;

    public ConversationService(ConversationRepository conversations) {
        this.conversations = conversations;
    }

    @Transactional
    public Conversation create(long creatorId, CreateConversationRequest request) {
        Set<Long> members = new LinkedHashSet<>(request.memberIds());
        members.add(creatorId); // creator is always a member

        long id = conversations.create(request.name());
        conversations.addMembers(id, new ArrayList<>(members));
        return new Conversation(id, request.name(), conversations.findMemberIds(id));
    }

    @Transactional(readOnly = true)
    public Conversation get(long conversationId, long userId) {
        String name = conversations.findName(conversationId)
                .orElseThrow(() -> new NotFoundException("Conversation " + conversationId + " not found"));
        requireMember(conversationId, userId);
        return new Conversation(conversationId, name, conversations.findMemberIds(conversationId));
    }

    public void requireMember(long conversationId, long userId) {
        if (!conversations.isMember(conversationId, userId)) {
            throw new ForbiddenException("User " + userId + " is not a member of conversation " + conversationId);
        }
    }

    public void requireExists(long conversationId) {
        if (conversations.findName(conversationId).isEmpty()) {
            throw new NotFoundException("Conversation " + conversationId + " not found");
        }
    }

    public List<Long> memberIds(long conversationId) {
        return conversations.findMemberIds(conversationId);
    }
}
