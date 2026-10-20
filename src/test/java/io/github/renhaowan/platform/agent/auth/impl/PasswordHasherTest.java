package io.github.renhaowan.platform.agent.auth.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    /** data.sql 里的种子串（admin123），格式与校验逻辑必须对得上。 */
    private static final String SEED_ADMIN =
            "pbkdf2$210000$k4qXz92OxljgfDqd9oDvhg==$jM4Skfi2GLyTL7++zGFTy3/XsHePsouva0OYMMC2gWg=";

    @Test
    void hashThenMatchesRoundTrips() {
        String stored = PasswordHasher.hash("s3cret");

        assertThat(stored).startsWith("pbkdf2$210000$");
        assertThat(PasswordHasher.matches("s3cret", stored)).isTrue();
        assertThat(PasswordHasher.matches("s3creT", stored)).isFalse();
    }

    @Test
    void samePasswordProducesDifferentDigests() {
        // 随机盐：相同明文两次入库不能得到相同摘要（防彩虹表与批量比对）
        assertThat(PasswordHasher.hash("same")).isNotEqualTo(PasswordHasher.hash("same"));
    }

    @Test
    void seedDigestIsVerifiable() {
        assertThat(PasswordHasher.matches("admin123", SEED_ADMIN)).isTrue();
        assertThat(PasswordHasher.matches("wrong", SEED_ADMIN)).isFalse();
    }

    @Test
    void legacySha256DigestStillVerifies() {
        String legacy = "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9";

        assertThat(PasswordHasher.isLegacy(legacy)).isTrue();
        assertThat(PasswordHasher.matches("admin123", legacy)).isTrue();
        assertThat(PasswordHasher.matches("admin124", legacy)).isFalse();
    }

    @Test
    void malformedStoredValueNeverMatches() {
        assertThat(PasswordHasher.matches("x", null)).isFalse();
        assertThat(PasswordHasher.matches("x", "pbkdf2$not-a-number$aaaa$bbbb")).isFalse();
        assertThat(PasswordHasher.matches("x", "pbkdf2$99999999$aaaa$bbbb")).isFalse();
        assertThat(PasswordHasher.matches("x", "plain-text")).isFalse();
    }
}
