package io.github.renhaowan.platform.agent.confirm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConfirmManagerTest {

    private final ConfirmManager manager = new ConfirmManager();

    @Test
    void approvalResumesAwait() throws Exception {
        String token = manager.request("s1", "cancel_booking", "{\"id\":1}");
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100);
                manager.complete(token, true);
            } catch (InterruptedException ignored) {
            }
        });

        assertThat(manager.await(token, 5)).isTrue();
        assertThat(manager.pendingOf(token)).isNull();
    }

    @Test
    void rejectionResumesAwaitWithFalse() throws Exception {
        String token = manager.request("s1", "cancel_booking", "{}");
        CompletableFuture.runAsync(() -> manager.complete(token, false));

        assertThat(manager.await(token, 5)).isFalse();
    }

    @Test
    void timeoutTreatedAsRejected() {
        String token = manager.request("s1", "cancel_booking", "{}");

        assertThat(manager.await(token, 0)).isFalse();
        assertThat(manager.pendingOf(token)).isNull();
    }

    @Test
    void completeUnknownTokenReturnsFalse() {
        assertThat(manager.complete("no-such-token", true)).isFalse();
    }
}
