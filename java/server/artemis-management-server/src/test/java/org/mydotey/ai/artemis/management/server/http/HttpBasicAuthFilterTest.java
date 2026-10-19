package org.mydotey.ai.artemis.management.server.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.server.auth.ManagementAuthConfigs;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpBasicAuthFilterTest {

    private final HttpBasicAuthFilter filter =
            new HttpBasicAuthFilter(ManagementAuthConfigs.enabled("artemis", "secret"));

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
    void should_reject_request_when_credentials_wrong() throws Exception {
        MockHttpServletRequest request = request("/admin/v1/services");
        request.addHeader("Authorization", BasicAuth.headerValue("artemis", "wrong"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void should_pass_request_when_credentials_valid() throws Exception {
        MockHttpServletRequest request = request("/admin/v1/services");
        request.addHeader("Authorization", BasicAuth.headerValue("artemis", "secret"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void should_pass_request_when_auth_disabled() throws Exception {
        HttpBasicAuthFilter disabled = new HttpBasicAuthFilter(ManagementAuthConfigs.disabled());
        MockFilterChain chain = new MockFilterChain();

        disabled.doFilter(request("/admin/v1/services"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
