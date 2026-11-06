package com.tilechat.common.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    @Test
    void backoffGrowsExponentiallyAndIsCapped() throws Exception {
        OutboxEvent e = new OutboxEvent("id", "x", "k", "{}");
        long[] expected = {1, 2, 4, 8, 16, 32, 60, 60};
        for (long seconds : expected) {
            Instant before = Instant.now();
            e.markFailed("boom");
            var field = OutboxEvent.class.getDeclaredField("nextAttemptAt");
            field.setAccessible(true);
            Instant next = (Instant) field.get(e);
            assertThat(next).isBetween(before.plusSeconds(seconds).minusMillis(50), before.plusSeconds(seconds).plusSeconds(1));
        }
        assertThat(e.getAttempts()).isEqualTo(expected.length);
    }
}
