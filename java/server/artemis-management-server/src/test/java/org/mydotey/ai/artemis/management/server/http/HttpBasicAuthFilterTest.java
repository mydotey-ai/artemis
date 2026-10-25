package org.mydotey.ai.artemis.management.server.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.application.AccountAuthService;
import org.mydotey.ai.artemis.management.domain.AccountAccessException;
import org.mydotey.ai.artemis.management.server.auth.ManagementAuthConfigs;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpBasicAuthFilterTest {

    private final StubAuthService authService = new StubAuthService();
    private final HttpBasicAuthFilter filter =
            new HttpBasicAuthFilter(ManagementAuthConfigs.enabled("artemis", "secret"), authService);

    @Test
    void should_reject_request_when_no_credentials() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/admin/v1/services"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void should_reject_request_when_rejected_status_is_unauthorized() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/admin/v1/services"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).contains("Basic");
    }

    @Test
    void should_reject_request_when_authenticator_rejects() throws Exception {
        MockHttpServletRequest request = request("/admin/v1/services");
        request.addHeader("Authorization", BasicAuth.headerValue("artemis", "wrong"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void should_pass_request_when_authenticator_accepts() throws Exception {
        authService.accept = true;
        MockHttpServletRequest request = request("/admin/v1/services");
        request.addHeader("Authorization", BasicAuth.headerValue("artemis", "secret"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void should_return_503_when_authenticator_unavailable() throws Exception {
        authService.unavailable = true;
        MockHttpServletRequest request = request("/admin/v1/services");
        request.addHeader("Authorization", BasicAuth.headerValue("artemis", "secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void should_pass_request_when_auth_disabled() throws Exception {
        HttpBasicAuthFilter disabled = new HttpBasicAuthFilter(ManagementAuthConfigs.disabled(), authService);
        MockFilterChain chain = new MockFilterChain();

        disabled.doFilter(request("/admin/v1/services"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private static final class StubAuthService implements AccountAuthService {

        boolean accept;
        boolean unavailable;

        @Override
        public boolean verify(String username, String password) {
            if (unavailable) {
                throw new AccountAccessException(AccountAccessException.ACCOUNT_STORE_UNAVAILABLE, "store down", null);
            }
            return accept;
        }

        @Override
        public String changePassword(String oldPassword, String newPassword) {
            return "artemis";
        }
    }
}
