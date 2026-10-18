package org.mydotey.ai.artemis.registry.server.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.server.auth.AuthConfig;
import org.mydotey.ai.artemis.registry.server.auth.AuthConfigs;
import org.mydotey.ai.artemis.registry.server.auth.BasicAuth;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpBasicAuthFilterTest {

    private final AuthConfig auth = AuthConfigs.enabled("artemis", "secret");
    private final HttpBasicAuthFilter filter = new HttpBasicAuthFilter(auth);

    @Test
    void should_reject_http_when_credentials_missing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/api/anything"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).contains("Basic");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void should_allow_http_when_credentials_valid() throws Exception {
        MockHttpServletRequest request = request("/api/anything");
        request.addHeader("Authorization", BasicAuth.headerValue("artemis", "secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void should_allow_http_when_scheme_case_differs() throws Exception {
        MockHttpServletRequest request = request("/api/anything");
        request.addHeader(
                "Authorization",
                "basic " + BasicAuth.headerValue("artemis", "secret").substring(6));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void should_allow_whitelisted_readyz_without_credentials() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/readyz"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void should_allow_readyz_under_context_path() throws Exception {
        MockHttpServletRequest request = request("/artemis/readyz");
        request.setContextPath("/artemis");
        request.setServletPath("/readyz");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void should_skip_http_auth_when_disabled() throws Exception {
        HttpBasicAuthFilter disabled = new HttpBasicAuthFilter(AuthConfigs.disabled());
        MockFilterChain chain = new MockFilterChain();

        disabled.doFilter(request("/api/anything"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
