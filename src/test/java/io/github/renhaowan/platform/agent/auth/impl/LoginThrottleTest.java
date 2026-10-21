package io.github.renhaowan.platform.agent.auth.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private final LoginThrottle throttle = new LoginThrottle();

    @Test
    void blocksAfterFiveFailures() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.onFailure("alice");
        }

        assertThat(throttle.isBlocked("alice")).isFalse();

        throttle.onFailure("alice");
        assertThat(throttle.isBlocked("alice")).isTrue();
    }

    @Test
    void successfulLoginClearsFailures() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            throttle.onFailure("bob");
        }
        assertThat(throttle.isBlocked("bob")).isTrue();

        throttle.onSuccess("bob");

        assertThat(throttle.isBlocked("bob")).isFalse();
    }

    @Test
    void usernamesAreCaseInsensitiveAndTrimmed() {
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            throttle.onFailure("Carol");
        }

        assertThat(throttle.isBlocked(" carol ")).isTrue();
    }

    @Test
    void differentUsersDoNotAffectEachOther() {
        throttle.onSuccess("dave");

        assertThat(throttle.isBlocked("erin")).isFalse();
    }

    @Test
    void trackedUsersStayBoundedUnderUsernameSpray() {
        for (int i = 0; i < LoginThrottle.MAX_TRACKED_USERS + 100; i++) {
            throttle.onFailure("spray-" + i);
        }

        assertThat(throttle.trackedUsers()).isLessThanOrEqualTo(LoginThrottle.MAX_TRACKED_USERS);
    }
}
