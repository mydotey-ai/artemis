package org.mydotey.ai.artemis.management.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    // PBKDF2 600k 单次约百 ms 级：用例共享最小哈希次数，控制套件时长
    private static final String RAW = "s3cret-password";
    private static final String HASH = PasswordHasher.hash(RAW);

    @Test
    void should_match_when_password_correct() {
        assertThat(PasswordHasher.matches(RAW, HASH)).isTrue();
    }

    @Test
    void should_not_match_when_password_wrong() {
        assertThat(PasswordHasher.matches("s3cret-passworD", HASH)).isFalse();
        assertThat(PasswordHasher.matches("", HASH)).isFalse();
    }

    @Test
    void should_return_false_when_stored_hash_malformed() {
        assertThat(PasswordHasher.matches(RAW, null)).isFalse();
        assertThat(PasswordHasher.matches(RAW, "")).isFalse();
        assertThat(PasswordHasher.matches(RAW, "not-a-hash")).isFalse();
        assertThat(PasswordHasher.matches(RAW, "md5$1000$QUJD$QUJD")).isFalse();
        // 非数字 iterations / 非正 iterations / 坏 base64
        assertThat(PasswordHasher.matches(RAW, "pbkdf2-sha256$abc$QUJD$QUJD")).isFalse();
        assertThat(PasswordHasher.matches(RAW, "pbkdf2-sha256$0$QUJD$QUJD")).isFalse();
        assertThat(PasswordHasher.matches(RAW, "pbkdf2-sha256$1000$!!!$???")).isFalse();
    }

    @Test
    void should_produce_distinct_hashes_when_same_password_twice() {
        String second = PasswordHasher.hash(RAW);

        assertThat(HASH).isNotEqualTo(second);
        assertThat(PasswordHasher.matches(RAW, second)).isTrue();
    }

    @Test
    void should_embed_parameters_in_hash_format() {
        assertThat(HASH).startsWith("pbkdf2-sha256$600000$");
    }
}
