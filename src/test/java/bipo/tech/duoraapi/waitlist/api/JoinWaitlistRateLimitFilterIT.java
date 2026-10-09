package bipo.tech.duoraapi.waitlist.api;

import static bipo.tech.duoraapi.RateLimitTestSupport.clearBuckets;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/** O filtro com os buckets no PostgreSQL real, com capacidade pequena para chegar logo ao limite. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JoinWaitlistRateLimitFilterIT {

    private static final int CAPACITY = 2;
    private static final Duration PERIOD = Duration.ofMinutes(10);
    private static final String CLIENT_A = "203.0.113.1";
    private static final String CLIENT_B = "203.0.113.2";

    @Autowired
    private ProxyManager<String> rateLimitBuckets;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    private JoinWaitlistRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        clearBuckets(jdbcClient);
        filter = new JoinWaitlistRateLimitFilter(rateLimitBuckets, CAPACITY, PERIOD);
    }

    @Test
    void letsRequestsThroughUpToCapacity() throws Exception {
        assertThat(join(filter, CLIENT_A).chain.getRequest()).isNotNull();
        assertThat(join(filter, CLIENT_A).chain.getRequest()).isNotNull();
    }

    @Test
    void rejectsRequestAboveCapacityWithRetryAfter() throws Exception {
        join(filter, CLIENT_A);
        join(filter, CLIENT_A);

        var rejected = join(filter, CLIENT_A);

        assertThat(rejected.chain.getRequest()).isNull();
        assertThat(rejected.response.getStatus()).isEqualTo(429);
        // Reposição gradual: 1 ficha a cada PERIOD / CAPACITY = 5 min = 300 s.
        assertThat(rejected.response.getHeader("Retry-After")).isEqualTo("300");
        assertThat(rejected.response.getContentType()).isEqualTo("application/problem+json");
    }

    @Test
    void countsEachClientSeparately() throws Exception {
        join(filter, CLIENT_A);
        join(filter, CLIENT_A);

        var otherClient = join(filter, CLIENT_B);

        assertThat(otherClient.chain.getRequest()).isNotNull();
        assertThat(otherClient.response.getStatus()).isEqualTo(200);
    }

    @Test
    void sharesLimitAcrossReplicas() throws Exception {
        var otherReplica = new JoinWaitlistRateLimitFilter(otherReplicaBuckets(), CAPACITY, PERIOD);
        join(filter, CLIENT_A);
        join(otherReplica, CLIENT_A);

        var rejected = join(filter, CLIENT_A);

        assertThat(rejected.response.getStatus()).isEqualTo(429);
    }

    /** Falha fechada (docs/adr/0006): sem o banco não há como contar, e a inscrição não passa. */
    @Test
    void rejectsRequestWithServiceUnavailableWhenBucketStoreIsDown() throws Exception {
        var unreachable = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unreachable");
        var withoutStore = new JoinWaitlistRateLimitFilter(bucketsOn(unreachable), CAPACITY, PERIOD);

        var rejected = join(withoutStore, CLIENT_A);

        assertThat(rejected.chain.getRequest()).isNull();
        assertThat(rejected.response.getStatus()).isEqualTo(503);
        assertThat(rejected.response.getContentType()).isEqualTo("application/problem+json");
    }

    /** Sem timeout, a requisição esperaria o lock da linha indefinidamente, segurando uma conexão. */
    @Test
    @Timeout(10)
    void rejectsRequestWithServiceUnavailableWhenBucketStaysLocked() throws Exception {
        join(filter, CLIENT_A);

        try (Connection lockHolder = dataSource.getConnection()) {
            // Se o filtro não tiver timeout, o PostgreSQL derruba esta sessão e solta o lock: o teste
            // falha pelo status, em vez de travar o build.
            try (var holderTimeout = lockHolder.createStatement()) {
                holderTimeout.execute("set idle_in_transaction_session_timeout = '5s'");
            }
            lockHolder.setAutoCommit(false);
            try (var lock = lockHolder.prepareStatement("select state from rate_limit_bucket where id = ? for update")) {
                lock.setString(1, JoinWaitlistRateLimitFilter.KEY_PREFIX + CLIENT_A);
                lock.executeQuery().close();
            }

            var rejected = join(filter, CLIENT_A);

            assertThat(rejected.chain.getRequest()).isNull();
            assertThat(rejected.response.getStatus()).isEqualTo(503);
            lockHolder.rollback();
        }
    }

    @Test
    void sharesLimitWithinIpv6Slash64() throws Exception {
        join(filter, "2001:db8:1:2::1");
        join(filter, "2001:db8:1:2:ffff:ffff:ffff:ffff");

        var rejected = join(filter, "2001:db8:1:2:abcd::7");

        assertThat(rejected.response.getStatus()).isEqualTo(429);
    }

    @Test
    void countsEachIpv6Slash64Separately() throws Exception {
        join(filter, "2001:db8:1:2::1");
        join(filter, "2001:db8:1:2::2");

        var otherNetwork = join(filter, "2001:db8:1:3::1");

        assertThat(otherNetwork.chain.getRequest()).isNotNull();
    }

    @Test
    void storesIpv6BucketUnderNetworkPrefix() throws Exception {
        join(filter, "2001:DB8:1:2:0:0:0:1");

        var keys = jdbcClient.sql("select id from rate_limit_bucket").query(String.class).list();
        assertThat(keys).containsExactly("join-waitlist:2001:db8:1:2::/64");
    }

    @Test
    void storesBucketUnderPrefixedClientKey() throws Exception {
        join(filter, CLIENT_A);

        var keys = jdbcClient.sql("select id from rate_limit_bucket").query(String.class).list();
        assertThat(keys).containsExactly("join-waitlist:" + CLIENT_A);
    }

    @Test
    void ignoresOtherRoutes() throws Exception {
        for (int i = 0; i <= CAPACITY; i++) {
            var outcome = send(filter, new MockHttpServletRequest("GET", "/api/admin/waitlist/stats"), CLIENT_A);
            assertThat(outcome.chain.getRequest()).isNotNull();
        }
        assertThat(jdbcClient.sql("select count(*) from rate_limit_bucket").query(Long.class).single()).isZero();
    }

    @Test
    void limitsRouteWhenAppRunsUnderContextPath() throws Exception {
        for (int i = 0; i < CAPACITY; i++) {
            send(filter, requestUnderContextPath(), CLIENT_A);
        }

        var rejected = send(filter, requestUnderContextPath(), CLIENT_A);

        assertThat(rejected.response.getStatus()).isEqualTo(429);
    }

    /** Outra réplica tem o próprio gerenciador de buckets; só o banco é comum. */
    private ProxyManager<String> otherReplicaBuckets() {
        return bucketsOn(dataSource);
    }

    private static ProxyManager<String> bucketsOn(DataSource dataSource) {
        return Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table("rate_limit_bucket")
                .build();
    }

    private static MockHttpServletRequest requestUnderContextPath() {
        var request = new MockHttpServletRequest("POST", "/duora" + JoinWaitlistRateLimitFilter.PATH);
        request.setContextPath("/duora");
        request.setServletPath(JoinWaitlistRateLimitFilter.PATH);
        return request;
    }

    private static Outcome join(JoinWaitlistRateLimitFilter filter, String clientIp) throws Exception {
        return send(filter, new MockHttpServletRequest("POST", JoinWaitlistRateLimitFilter.PATH), clientIp);
    }

    private static Outcome send(JoinWaitlistRateLimitFilter filter, MockHttpServletRequest request, String clientIp)
            throws Exception {
        request.setRemoteAddr(clientIp);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain);
    }

    private record Outcome(MockHttpServletResponse response, MockFilterChain chain) {
    }

}
