package com.tilechat.chat.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "conversation_members",
        uniqueConstraints = @UniqueConstraint(name = "uk_member_conv_user", columnNames = {"conversation_id", "user_id"}),
        indexes = @Index(name = "idx_member_user", columnList = "user_id"))
public class ConversationMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MemberStatus status;

    /**
     * Never shown to this member. Set when they had blocked the person who started the chat or
     * invited them (silent block: the other side sees a normal pending request).
     */
    @Column(nullable = false)
    private boolean hidden;

    @Column(name = "invited_by")
    private Long invitedBy;

    /** Messages up to this seq are hidden for this member ("delete chat" clears history for you only). */
    @Column(name = "cleared_seq", nullable = false)
    private long clearedSeq;

    /** This member deleted the chat; it stays out of their list until something new arrives. */
    @Column(nullable = false)
    private boolean deleted;

    protected ConversationMember() {}

    private ConversationMember(Long conversationId, Long userId, MemberStatus status, Long invitedBy, boolean hidden) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.status = status;
        this.invitedBy = invitedBy;
        this.hidden = hidden;
        this.joinedAt = Instant.now();
    }

    public static ConversationMember owner(Long conversationId, Long userId) {
        return new ConversationMember(conversationId, userId, MemberStatus.ACCEPTED, null, false);
    }

    public static ConversationMember invited(Long conversationId, Long userId, Long invitedBy, boolean hidden) {
        return new ConversationMember(conversationId, userId, MemberStatus.PENDING, invitedBy, hidden);
    }

    public boolean isVisible() {
        return !hidden && !deleted && status != MemberStatus.REJECTED;
    }

    public void accept() {
        status = MemberStatus.ACCEPTED;
        hidden = false;
        deleted = false;
        joinedAt = Instant.now();
    }

    public void reject() { status = MemberStatus.REJECTED; }

    public void reopenRequest() {
        if (status == MemberStatus.REJECTED) status = MemberStatus.PENDING;
    }

    public void hide() { hidden = true; }

    /** Delete the chat for this member: hide it and everything up to {@code lastSeq}. */
    public void deleteUpTo(long lastSeq) {
        deleted = true;
        clearedSeq = Math.max(clearedSeq, lastSeq);
    }

    /** Something new arrived: the chat comes back (history before clearedSeq stays hidden). */
    public void restore() { deleted = false; }

    public void unhide() { hidden = false; }

    public Long getConversationId() { return conversationId; }
    public Long getUserId() { return userId; }
    public MemberStatus getStatus() { return status; }
    public boolean isHidden() { return hidden; }
    public Long getInvitedBy() { return invitedBy; }
    public long getClearedSeq() { return clearedSeq; }
    public boolean isDeleted() { return deleted; }
}
