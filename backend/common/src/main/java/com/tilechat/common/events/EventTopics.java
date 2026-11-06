package com.tilechat.common.events;

/** Exchange names and routing keys shared by publishers and consumers. */
public final class EventTopics {
    private EventTopics() {}

    public static final String EXCHANGE = "tilechat.events";
    public static final String DEAD_LETTER_EXCHANGE = "tilechat.events.dlx";

    public static final String USER_REGISTERED = "user.registered";
    public static final String USER_UPDATED = "user.updated";
    public static final String MESSAGE_CREATED = "message.created";
    public static final String MEMBERS_ADDED = "group.members-added";
}
