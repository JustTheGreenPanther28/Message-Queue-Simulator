package com.tilechat.chat;

import com.tilechat.chat.domain.MessageStatus;
import com.tilechat.chat.dto.ConversationDto;
import com.tilechat.chat.dto.MemberDto;
import com.tilechat.chat.dto.MessageDto;
import com.tilechat.chat.dto.Requests;
import com.tilechat.chat.repository.MessageRepository;
import com.tilechat.chat.service.BlockService;
import com.tilechat.chat.service.ConversationViews;
import com.tilechat.chat.service.ReceiptService;
import com.tilechat.common.security.AuthUser;
import com.tilechat.common.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/** Message requests, group invites and silent blocking. */
class RequestsAndBlocksIT extends ChatIntegrationTest {

    @Autowired ReceiptService receipts;
    @Autowired BlockService blocks;
    @Autowired ConversationViews views;
    @Autowired MessageRepository messageRepo;

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

    private Optional<ConversationDto> visibleTo(AuthUser u, Long conversationId) {
        return views.listFor(u.id()).stream().filter(c -> c.id().equals(conversationId)).findFirst();
    }

    private String statusOf(ConversationDto c, AuthUser u) {
        return c.members().stream().filter(m -> m.id().equals(u.id())).map(MemberDto::status).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ requests

    @Test
    void newDirectChatIsARequestUntilAccepted() {
        ConversationDto c = conversations.openDirect(alice, bob.id());
        MessageDto m = say(alice, c.id(), "hi bob");

        ConversationDto bobsView = visibleTo(bob, c.id()).orElseThrow();
        assertThat(statusOf(bobsView, bob)).isEqualTo("PENDING");
        assertThat(messages.history(bob, c.id(), null, null, 10)).as("bob can preview the request").hasSize(1);
        assertThatThrownBy(() -> say(bob, c.id(), "hello?")).as("but not reply yet")
                .isInstanceOf(ApiException.class).hasMessageContaining("Accept");

        receipts.markDelivered(bob.id(), List.of(m.id()));
        receipts.markRead(bob.id(), c.id());
        assertThat(status(m)).as("no receipts are sent for an unanswered request").isEqualTo(MessageStatus.SENT);

        conversations.accept(bob, c.id());
        conversations.accept(bob, c.id()); // idempotent
        assertThat(status(m)).as("accepting confirms delivery").isEqualTo(MessageStatus.DELIVERED);
        receipts.markRead(bob.id(), c.id());
        assertThat(status(m)).isEqualTo(MessageStatus.READ);
        assertThat(say(bob, c.id(), "hey!").id()).isNotNull();
    }

    @Test
    void openingTheChatYourselfAcceptsTheRequest() {
        ConversationDto c = conversations.openDirect(alice, bob.id());
        conversations.openDirect(bob, alice.id());
        assertThat(statusOf(visibleTo(bob, c.id()).orElseThrow(), bob)).isEqualTo("ACCEPTED");
    }

    @Test
    void rejectedRequestDisappearsAndANewMessageBringsItBack() {
        ConversationDto c = conversations.openDirect(alice, bob.id());
        say(alice, c.id(), "first");
        conversations.reject(bob, c.id());
        conversations.reject(bob, c.id()); // idempotent

        assertThat(visibleTo(bob, c.id())).isEmpty();
        assertThat(statusOf(visibleTo(alice, c.id()).orElseThrow(), bob)).as("alice isn't told").isEqualTo("PENDING");

        say(alice, c.id(), "second try");
        assertThat(visibleTo(bob, c.id())).as("a new message makes it a request again").isPresent();
    }

    @Test
    void groupInvitesMustBeAcceptedBeforeSeeingOrReceivingMessages() {
        ConversationDto g = conversations.createGroup(alice, new Requests.CreateGroup("team", List.of(bob.id(), carol.id())), null);
        conversations.accept(bob, g.id());
        MessageDto m = say(alice, g.id(), "hi team");

        assertThatThrownBy(() -> messages.history(carol, g.id(), null, null, 10)).isInstanceOf(ApiException.class);
        receipts.markRead(bob.id(), g.id());
        assertThat(status(m)).as("pending invitees don't hold up read status").isEqualTo(MessageStatus.READ);

        conversations.reject(carol, g.id());
        assertThat(visibleTo(carol, g.id())).isEmpty();
        assertThat(visibleTo(alice, g.id()).orElseThrow().members()).extracting(MemberDto::id).doesNotContain(carol.id());
    }

    // ------------------------------------------------------------------ silent block

    @Test
    void blockedSendersMessagesAreSilentlyDropped() {
        ConversationDto c = direct(alice, bob);
        MessageDto before = say(alice, c.id(), "before block");
        blocks.block(bob, alice.id());
        blocks.block(bob, alice.id()); // idempotent

        MessageDto during = say(alice, c.id(), "can you see this?");
        assertThat(during.id()).as("alice notices nothing").isNotNull();
        assertThat(messages.history(bob, c.id(), null, null, 10)).extracting(MessageDto::id)
                .contains(before.id()).doesNotContain(during.id());
        assertThat(visibleTo(bob, c.id()).orElseThrow().lastMessage().id()).as("bob's preview skips it").isEqualTo(before.id());

        receipts.markDelivered(bob.id(), null);
        assertThat(status(during)).as("alice sees one tick forever").isEqualTo(MessageStatus.SENT);
        assertThatThrownBy(() -> say(bob, c.id(), "x")).as("bob must unblock to reply")
                .isInstanceOf(ApiException.class).hasMessageContaining("Unblock");

        blocks.unblock(bob, alice.id());
        MessageDto after = say(alice, c.id(), "after unblock");
        assertThat(messages.history(bob, c.id(), null, null, 10)).extracting(MessageDto::id)
                .contains(after.id()).as("messages sent while blocked stay hidden").doesNotContain(during.id());
    }

    @Test
    void blockedUsersRequestsAndInvitesNeverReachYou() {
        blocks.block(bob, alice.id());
        ConversationDto direct = conversations.openDirect(alice, bob.id());
        say(alice, direct.id(), "hello?");
        ConversationDto group = conversations.createGroup(alice, new Requests.CreateGroup("party", List.of(bob.id(), carol.id())), null);

        assertThat(visibleTo(bob, direct.id())).isEmpty();
        assertThat(visibleTo(bob, group.id())).isEmpty();
        assertThat(visibleTo(carol, group.id())).as("others are invited normally").isPresent();
        assertThat(statusOf(visibleTo(alice, group.id()).orElseThrow(), bob)).as("alice just sees 'invited'").isEqualTo("PENDING");
        assertThatThrownBy(() -> conversations.accept(bob, group.id())).isInstanceOf(ApiException.class);
    }

    @Test
    void blockingHidesPendingRequestsFromThatPerson() {
        ConversationDto c = conversations.openDirect(alice, bob.id());
        say(alice, c.id(), "spam");
        assertThat(visibleTo(bob, c.id())).isPresent();
        blocks.block(bob, alice.id());
        assertThat(visibleTo(bob, c.id())).isEmpty();
        assertThat(blocks.blocked(bob.id())).extracting(MemberDto::id).containsExactly(alice.id());
    }

    @Test
    void blocksDoNotAffectSharedGroups() {
        ConversationDto g = group(alice, bob, carol);
        blocks.block(bob, alice.id());
        MessageDto m = say(alice, g.id(), "group message");
        assertThat(messages.history(bob, g.id(), null, null, 10)).extracting(MessageDto::id).contains(m.id());
    }

    // ------------------------------------------------------------------ delete chat

    @Test
    void deletingAChatHidesItAndClearsHistoryForYouOnly() {
        ConversationDto c = direct(alice, bob);
        say(alice, c.id(), "one");
        MessageDto unread = say(alice, c.id(), "two");

        conversations.deleteChat(bob, c.id());
        conversations.deleteChat(bob, c.id()); // idempotent
        assertThat(visibleTo(bob, c.id())).isEmpty();
        assertThat(messages.history(alice, c.id(), null, null, 10)).as("alice keeps her copy").hasSize(2);
        assertThat(status(unread)).as("deleting doesn't fake a read receipt").isNotEqualTo(MessageStatus.READ);

        MessageDto fresh = say(alice, c.id(), "are you there?");
        ConversationDto back = visibleTo(bob, c.id()).orElseThrow();
        assertThat(back.unreadCount()).as("old unread messages don't count").isEqualTo(1);
        assertThat(messages.history(bob, c.id(), null, null, 10)).extracting(MessageDto::id).containsExactly(fresh.id());
    }

    @Test
    void reopeningADeletedChatYourselfStartsEmpty() {
        ConversationDto c = direct(alice, bob);
        say(bob, c.id(), "old");
        conversations.deleteChat(alice, c.id());
        ConversationDto reopened = conversations.openDirect(alice, bob.id());
        assertThat(reopened.id()).isEqualTo(c.id());
        assertThat(reopened.lastMessage()).isNull();
        assertThat(messages.history(alice, c.id(), null, null, 10)).isEmpty();
    }

    @Test
    void deletingAGroupChatLeavesTheGroup() {
        ConversationDto g = group(alice, bob, carol);
        conversations.deleteChat(carol, g.id());
        assertThat(visibleTo(carol, g.id())).isEmpty();
        assertThat(visibleTo(alice, g.id()).orElseThrow().members()).extracting(MemberDto::id).doesNotContain(carol.id());
    }
}
