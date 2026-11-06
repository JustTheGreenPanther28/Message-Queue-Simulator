package com.tilechat.chat.domain;

/**
 * Aggregate status of a message as the sender sees it. Ordered: a status only ever moves
 * forward (SENT -> DELIVERED -> READ), never back.
 * DELIVERED = every recipient's device has received it; READ = every recipient opened it.
 */
public enum MessageStatus {
    SENT, DELIVERED, READ;

    public int level() { return ordinal(); }

    public static MessageStatus ofLevel(int level) { return values()[level]; }
}
