package bipo.tech.duoraapi.waitlist.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JoinWaitlistRateLimitFilterTest {

    private static final int CAPACITY = 2;
    private static final Duration PERIOD = Duration.ofMinutes(10);
    private static final String CLIENT_A = "203.0.113.1";
    private static final String CLIENT_B = "203.0.113.2";

    private final JoinWaitlistRateLimitFilter filter = new JoinWaitlistRateLimitFilter(CAPACITY, PERIOD);

    @Test
    void letsRequestsThroughUpToCapacity() throws Exception {
        assertThat(join(CLIENT_A).chain.getRequest()).isNotNull();
        assertThat(join(CLIENT_A).chain.getRequest()).isNotNull();
    }

    @Test
    void rejectsRequestAboveCapacityWithRetryAfter() throws Exception {
        join(CLIENT_A);
        join(CLIENT_A);

        var rejected = join(CLIENT_A);

        assertThat(rejected.chain.getRequest()).isNull();
        assertThat(rejected.response.getStatus()).isEqualTo(429);
        // Reposição gradual: 1 ficha a cada PERIOD / CAPACITY = 5 min = 300 s.
        assertThat(rejected.response.getHeader("Retry-After")).isEqualTo("300");
        assertThat(rejected.response.getContentType()).isEqualTo("application/problem+json");
    }

    @Test
    void countsEachClientSeparately() throws Exception {
        join(CLIENT_A);
        join(CLIENT_A);

        var otherClient = join(CLIENT_B);

        assertThat(otherClient.chain.getRequest()).isNotNull();
        assertThat(otherClient.response.getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresOtherRoutes() throws Exception {
        for (int i = 0; i <= CAPACITY; i++) {
            var outcome = send(new MockHttpServletRequest("GET", "/api/admin/waitlist/stats"), CLIENT_A);
            assertThat(outcome.chain.getRequest()).isNotNull();
        }
    }

    private Outcome join(String clientIp) throws Exception {
        return send(new MockHttpServletRequest("POST", JoinWaitlistRateLimitFilter.PATH), clientIp);
    }

    private Outcome send(MockHttpServletRequest request, String clientIp) throws Exception {
        request.setRemoteAddr(clientIp);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain);
    }

    private record Outcome(MockHttpServletResponse response, MockFilterChain chain) {
    }

}
