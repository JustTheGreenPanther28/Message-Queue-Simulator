package com.tilechat.chat.domain;

/**
 * PENDING: someone started a chat with you / invited you to a group and you haven't answered.
 * ACCEPTED: a normal participant. REJECTED: you declined a direct-chat request (a new message
 * from that person turns it back into a request; block them to stop that).
 */
public enum MemberStatus { PENDING, ACCEPTED, REJECTED }
