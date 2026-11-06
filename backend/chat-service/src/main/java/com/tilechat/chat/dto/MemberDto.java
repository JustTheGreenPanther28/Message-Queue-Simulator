package com.tilechat.chat.dto;

/**
 * A person as shown in a conversation. {@code status} is ACCEPTED or PENDING (invited / hasn't
 * answered the request); it's null where it doesn't apply (profile updates, block list).
 * A declined or silently-hidden request is shown to others as PENDING, so blocking stays silent.
 */
public record MemberDto(Long id, String username, String displayName, String avatarUrl, String status) {

    public static MemberDto unknown(Long id) {
        return new MemberDto(id, "user" + id, "User " + id, null, null);
    }

    public MemberDto withStatus(String status) {
        return new MemberDto(id, username, displayName, avatarUrl, status);
    }
}
