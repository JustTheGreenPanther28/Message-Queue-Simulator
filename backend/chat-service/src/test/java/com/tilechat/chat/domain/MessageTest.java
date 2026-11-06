package com.tilechat.chat.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTest {

    @Test
    void statusOnlyMovesForward() {
        Message m = new Message(1L, 1, 1L, "c1", MessageType.TEXT, "hi", null);
        assertThat(m.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(m.advanceTo(MessageStatus.READ)).isTrue();
        assertThat(m.advanceTo(MessageStatus.DELIVERED)).isFalse();   // late "delivered" ack can't downgrade
        assertThat(m.advanceTo(MessageStatus.READ)).isFalse();        // repeat is a no-op
        assertThat(m.getStatus()).isEqualTo(MessageStatus.READ);
    }

    @Test
    void directKeyIsOrderIndependent() {
        assertThat(Conversation.directKey(7L, 3L)).isEqualTo(Conversation.directKey(3L, 7L)).isEqualTo("3:7");
    }
}
