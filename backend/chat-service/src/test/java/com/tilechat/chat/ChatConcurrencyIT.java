package com.tilechat.chat;

import com.tilechat.chat.domain.MessageStatus;
import com.tilechat.chat.dto.ConversationDto;
import com.tilechat.chat.dto.MessageDto;
import com.tilechat.chat.dto.Requests;
import com.tilechat.chat.repository.ConversationRepository;
import com.tilechat.chat.repository.MessageRepository;
import com.tilechat.chat.service.ReceiptService;
import com.tilechat.common.outbox.OutboxRepository;
import com.tilechat.common.security.AuthUser;
import com.tilechat.common.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Hammers the write paths from many threads against a real Postgres and checks the
 * invariants: no duplicate messages, gap-free ordering, statuses that converge,
 * and race-free conversation creation.
 */
class ChatConcurrencyIT extends ChatIntegrationTest {

    @Autowired ReceiptService receipts;
    @Autowired ConversationRepository conversationRepo;
    @Autowired MessageRepository messageRepo;
    @Autowired OutboxRepository outbox;

    AuthUser alice, bob, carol;

    @BeforeEach
    void users() {
        alice = user("alice");
        bob = user("bob");
        carol = user("carol");
    }

    private MessageStatus status(MessageDto m) {
        return messageRepo.findById(m.id()).orElseThrow().getStatus();
    }

    @Test
    void retriedSendWithSameClientIdCreatesExactlyOneMessage() throws Exception {
        ConversationDto c = direct(alice, bob);
        String clientId = UUID.randomUUID().toString();

        List<MessageDto> results = concurrently(16,
                i -> messages.send(alice, c.id(), new Requests.SendMessage(clientId, "hello", null)));

        assertThat(results).extracting(MessageDto::id).containsOnly(results.get(0).id());
        assertThat(messages.history(alice, c.id(), null, null, 100)).hasSize(1);
        long events = outbox.findAll().stream().filter(e -> e.getPayload().contains("\"messageId\":" + results.get(0).id() + ",")).count();
        assertThat(events).as("one notification event, not one per retry").isEqualTo(1);
    }

    @Test
    void concurrentSendsGetGapFreeIncreasingSequenceNumbers() throws Exception {
        ConversationDto c = group(alice, bob, carol);
        AuthUser[] senders = {alice, bob, carol};

        concurrently(30, i -> say(senders[i % 3], c.id(), "m" + i));

        List<MessageDto> history = messages.history(alice, c.id(), null, null, 100);
        assertThat(history).extracting(MessageDto::seq)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 30).boxed().toList());
        assertThat(conversations.addMembers(alice, c.id(), new Requests.AddMembers(List.of(bob.id()))).lastMessage().seq())
                .as("lastMessage always points at the highest seq").isEqualTo(30);
    }

    @Test
    void simultaneousLastReadersStillProduceReadStatus() throws Exception {
        // Without the message row lock, both readers could see "one reader left" and the status would stay DELIVERED.
        for (int round = 0; round < 10; round++) {
            ConversationDto c = group(alice, bob, carol);
            MessageDto m = say(alice, c.id(), "x");

            AuthUser[] readers = {bob, carol};
            concurrently(2, i -> { receipts.markRead(readers[i].id(), c.id()); return null; });

            assertThat(status(m)).isEqualTo(MessageStatus.READ);
        }
    }

    @Test
    void deliveryNeedsEveryRecipientAndAcksAreIdempotent() {
        ConversationDto c = group(alice, bob, carol);
        MessageDto m = say(alice, c.id(), "x");

        receipts.markDelivered(bob.id(), List.of(m.id()));
        receipts.markDelivered(bob.id(), List.of(m.id())); // repeated ack
        assertThat(status(m)).isEqualTo(MessageStatus.SENT);

        receipts.markDelivered(carol.id(), null); // "everything I've synced"
        assertThat(status(m)).isEqualTo(MessageStatus.DELIVERED);

        receipts.markRead(bob.id(), c.id());
        conversations.leave(carol, c.id()); // carol leaves without reading: she must not block READ forever
        assertThat(status(m)).isEqualTo(MessageStatus.READ);
    }

    @Test
    void bothUsersOpeningTheSameDirectChatAtOnceGetOneConversation() throws Exception {
        AuthUser[] who = {alice, bob};
        List<ConversationDto> results = concurrently(12, i -> conversations.openDirect(who[i % 2], who[(i + 1) % 2].id()));
        assertThat(results).extracting(ConversationDto::id).containsOnly(results.get(0).id());
    }

    @Test
    void groupCreationWithSameIdempotencyKeyCreatesOneGroup() throws Exception {
        String key = UUID.randomUUID().toString();
        long before = conversationRepo.count();
        List<ConversationDto> results = concurrently(8, i -> conversations.createGroup(alice,
                new Requests.CreateGroup("team", List.of(bob.id(), carol.id())), key));
        assertThat(results).extracting(ConversationDto::id).containsOnly(results.get(0).id());
        assertThat(conversationRepo.count()).isEqualTo(before + 1);
    }

    @Test
    void outsidersCannotReadOrWrite() {
        ConversationDto c = direct(alice, bob);
        assertThatThrownBy(() -> messages.history(carol, c.id(), null, null, 10)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> say(carol, c.id(), "hi")).isInstanceOf(ApiException.class).hasMessageContaining("not found");
    }
}
