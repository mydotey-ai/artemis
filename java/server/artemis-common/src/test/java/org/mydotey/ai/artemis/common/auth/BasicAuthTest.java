package org.mydotey.ai.artemis.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class BasicAuthTest {

    @Test
    void should_decode_basic_header_roundtrip() {
        BasicAuth.Credential credential = BasicAuth.decode(BasicAuth.headerValue("artemis", "s3cret:pw"));

        assertThat(credential).isNotNull();
        assertThat(credential.username()).isEqualTo("artemis");
        assertThat(credential.password()).isEqualTo("s3cret:pw");
    }

    @Test
    void should_decode_when_scheme_case_and_spaces_vary() {
        assertThat(BasicAuth.decode("basic " + token("artemis", "pw")).username())
                .isEqualTo("artemis");
        assertThat(BasicAuth.decode("  Basic   " + token("artemis", "pw")).username())
                .isEqualTo("artemis");
    }

    @Test
    void should_return_null_when_header_malformed() {
        assertThat(BasicAuth.decode(null)).isNull();
        assertThat(BasicAuth.decode("Bearer some-token")).isNull();
        assertThat(BasicAuth.decode("Basic !!!not-base64!!!")).isNull();
        // 解码后缺冒号：无密码分隔符
        assertThat(BasicAuth.decode(
                        "Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes(StandardCharsets.UTF_8))))
                .isNull();
    }

    @Test
    void should_match_when_scheme_case_and_spaces_vary() {
        String expected = BasicAuth.headerValue("artemis", "pw");

        assertThat(BasicAuth.matches("basic " + token("artemis", "pw"), expected))
                .isTrue();
        assertThat(BasicAuth.matches("  Basic   " + token("artemis", "pw"), expected))
                .isTrue();
        assertThat(BasicAuth.matches("Basic " + token("artemis", "other"), expected))
                .isFalse();
        assertThat(BasicAuth.matches(null, expected)).isFalse();
    }

    private static String token(String username, String password) {
        return Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
