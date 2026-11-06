package com.tilechat.chat.service;

import com.tilechat.chat.domain.Message;
import com.tilechat.chat.domain.UserProfile;
import com.tilechat.chat.dto.MemberDto;
import com.tilechat.chat.dto.MessageDto;
import com.tilechat.common.storage.ObjectStorage;
import org.springframework.stereotype.Component;

/** Entity → API shape. Image keys become presigned URLs here, at the last moment. */
@Component
public class DtoMapper {

    private final ObjectStorage storage;

    public DtoMapper(ObjectStorage storage) {
        this.storage = storage;
    }

    public MessageDto message(Message m) {
        return new MessageDto(m.getId(), m.getConversationId(), m.getSeq(), m.getSenderId(), m.getClientMessageId(),
                m.getType(), m.getContent(), storage.urlFor(m.getImageKey()), m.getStatus(), m.getCreatedAt());
    }

    public MemberDto member(UserProfile p) {
        return new MemberDto(p.getId(), p.getUsername(), p.getDisplayName(), storage.urlFor(p.getAvatarKey()), null);
    }
}
