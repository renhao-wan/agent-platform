package io.github.renhaowan.platform.agent.sse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionLockRegistryTest {

    private final SessionLockRegistry locks = new SessionLockRegistry();

    @Test
    void sameSessionCannotRunTwice() {
        assertThat(locks.tryAcquire("sk-1")).isTrue();
        assertThat(locks.tryAcquire("sk-1")).isFalse();

        locks.release("sk-1");

        assertThat(locks.tryAcquire("sk-1")).isTrue();
    }

    @Test
    void differentSessionsRunIndependently() {
        assertThat(locks.tryAcquire("sk-1")).isTrue();
        assertThat(locks.tryAcquire("sk-2")).isTrue();
    }

    @Test
    void releaseUnknownSessionIsNoop() {
        locks.release("never-acquired");
        assertThat(locks.tryAcquire("never-acquired")).isTrue();
    }
}
