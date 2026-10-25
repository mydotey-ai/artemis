package org.mydotey.ai.artemis.management.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.management.application.AccountAuthService;

/** 内存态裁决（ADR-021 双轨）：v0.1 配置凭证语义零变化。 */
class ConfigAccountAuthServiceTest {

    private final ManagementAuthConfig authConfig = ManagementAuthConfigs.enabled("artemis", "secret");
    private final AccountAuthService service = new ConfigAccountAuthService(authConfig);

    @Test
    void should_verify_when_configured_credentials_match() {
        assertThat(service.verify("artemis", "secret")).isTrue();
    }

    @Test
    void should_not_verify_when_credentials_mismatch_or_request_password_blank() {
        assertThat(service.verify("artemis", "wrong")).isFalse();
        assertThat(service.verify("other", "secret")).isFalse();
        // 请求侧空白密码拒绝（v0.1 headerValueOrNull 语义；配置侧空白由 SCF 回落默认，ADR-009）
        assertThat(service.verify("artemis", "")).isFalse();
        assertThat(service.verify("artemis", null)).isFalse();
    }

    @Test
    void should_honor_runtime_credential_rotation() {
        ManagementAuthConfig rotated = ManagementAuthConfigs.enabled("artemis", "rotated");
        ConfigAccountAuthService service = new ConfigAccountAuthService(rotated);

        assertThat(service.verify("artemis", "secret")).isFalse();
        assertThat(service.verify("artemis", "rotated")).isTrue();
    }

    @Test
    void should_reject_change_when_memory_mode() {
        assertThatThrownBy(() -> service.changePassword("secret", "new-s3cret-1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
