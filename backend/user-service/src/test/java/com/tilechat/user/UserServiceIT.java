package com.tilechat.user;

import com.tilechat.common.outbox.OutboxRepository;
import com.tilechat.common.web.ApiException;
import com.tilechat.user.dto.*;
import com.tilechat.user.service.AuthService;
import com.tilechat.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class UserServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired AuthService auth;
    @Autowired UserService users;
    @Autowired OutboxRepository outbox;

    private static String uniqueName() {
        return "u" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void registerThenLoginIsCaseInsensitiveAndPublishesEvent() {
        String name = uniqueName();
        long eventsBefore = outbox.count();
        AuthResponse reg = auth.register(new RegisterRequest(name.toUpperCase(), "Alice", "secret123"));

        assertThat(reg.token()).isNotBlank();
        assertThat(reg.user().username()).isEqualTo(name);
        assertThat(auth.login(new LoginRequest(name, "secret123")).user().id()).isEqualTo(reg.user().id());
        assertThat(outbox.count()).as("user.registered written to outbox").isEqualTo(eventsBefore + 1);
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameError() {
        String name = uniqueName();
        auth.register(new RegisterRequest(name, "Bob", "secret123"));
        assertThatThrownBy(() -> auth.login(new LoginRequest(name, "nope")))
                .isInstanceOf(ApiException.class).hasMessage("Invalid username or password");
        assertThatThrownBy(() -> auth.login(new LoginRequest(uniqueName(), "nope")))
                .isInstanceOf(ApiException.class).hasMessage("Invalid username or password");
    }

    @Test
    void concurrentSignUpsWithTheSameUsernameHaveExactlyOneWinner() throws Exception {
        String name = uniqueName();
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return auth.register(new RegisterRequest(name, "Same", "secret123"));
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        start.countDown();
        int ok = 0, conflicts = 0;
        for (Future<Object> f : futures) {
            Object r = f.get(30, TimeUnit.SECONDS);
            if (r instanceof AuthResponse) ok++;
            else if (r instanceof ApiException e && e.status() == HttpStatus.CONFLICT) conflicts++;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflicts).isEqualTo(n - 1);
    }

    @Test
    void staleProfileUpdateIsRejected() {
        UserDto u = auth.register(new RegisterRequest(uniqueName(), "Carol", "secret123")).user();
        var v1 = users.updateProfile(u.id(), new UpdateProfileRequest("Carol A", u.version()));
        assertThat(v1.getVersion()).isGreaterThan(u.version());

        // A second tab still holding the old version must not overwrite the newer change.
        assertThatThrownBy(() -> users.updateProfile(u.id(), new UpdateProfileRequest("Carol B", u.version())))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(users.get(u.id()).displayName()).isEqualTo("Carol A");
    }
}
