package com.tilechat.chat;

import com.tilechat.chat.domain.UserProfile;
import com.tilechat.chat.dto.ConversationDto;
import com.tilechat.chat.dto.MessageDto;
import com.tilechat.chat.dto.Requests;
import com.tilechat.chat.repository.UserProfileRepository;
import com.tilechat.chat.service.ConversationService;
import com.tilechat.chat.service.MessageService;
import com.tilechat.common.security.AuthUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Base for chat-service integration tests: one Postgres container for all test classes
 * (singleton pattern, so Spring's cached context never points at a stopped container).
 */
@SpringBootTest
@ActiveProfiles("test")
abstract class ChatIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final AtomicLong IDS = new AtomicLong(1000);

    @Autowired protected ConversationService conversations;
    @Autowired protected MessageService messages;
    @Autowired protected UserProfileRepository profiles;

    protected AuthUser user(String name) {
        long id = IDS.incrementAndGet();
        UserProfile p = new UserProfile(id);
        p.apply(name + id, name, null, 0);
        profiles.save(p);
        return new AuthUser(id, name + id);
    }

    protected MessageDto say(AuthUser from, Long conversationId, String text) {
        return messages.send(from, conversationId, new Requests.SendMessage(UUID.randomUUID().toString(), text, null));
    }

    /** A group where everyone has accepted the invite. */
    protected ConversationDto group(AuthUser owner, AuthUser... others) {
        ConversationDto c = conversations.createGroup(owner,
                new Requests.CreateGroup("g", Arrays.stream(others).map(AuthUser::id).toList()), null);
        for (AuthUser o : others) conversations.accept(o, c.id());
        return c;
    }

    /** A direct chat both sides have accepted. */
    protected ConversationDto direct(AuthUser a, AuthUser b) {
        ConversationDto c = conversations.openDirect(a, b.id());
        conversations.accept(b, c.id());
        return c;
    }

    /** Runs n tasks that all start at the same instant. */
    protected static <T> List<T> concurrently(int n, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = IntStream.range(0, n)
                    .mapToObj(i -> pool.submit(() -> { start.await(); return task.apply(i); })).toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
