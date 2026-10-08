package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;

import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.identity.AccountId;

/** O limite por conta com os buckets no PostgreSQL real, com capacidade pequena para chegar logo ao fim. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountRateLimitIT {

    private static final String PREFIX = "sample:";
    private static final int CAPACITY = 2;
    private static final Duration PERIOD = Duration.ofHours(1);
    private static final AccountId ANA = new AccountId(UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b"));
    private static final AccountId BRUNO = new AccountId(UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8c"));

    @Autowired
    private ProxyManager<String> rateLimitBuckets;

    @Autowired
    private JdbcClient jdbcClient;

    private AccountRateLimit limit;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from rate_limit_bucket").update();
        limit = new AccountRateLimit(rateLimitBuckets, PREFIX, CAPACITY, PERIOD);
    }

    /** Reposição gradual: uma chamada a cada PERIOD / CAPACITY = 30 min. */
    @Test
    void rejectsAboveCapacityWithTheTimeToTheNextCall() {
        limit.consume(ANA);
        limit.consume(ANA);

        assertThatThrownBy(() -> limit.consume(ANA))
                .isInstanceOfSatisfying(RateLimitExceededException.class, exceeded ->
                        assertThat(exceeded.retryAfter()).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(30)));
    }

    /** A tabela é compartilhada com outros limites: a chave é "<limite>:<conta>" (docs/adr/0006). */
    @Test
    void keepsTheBucketUnderThePrefixAndTheAccount() {
        limit.consume(ANA);

        var keys = jdbcClient.sql("select id from rate_limit_bucket").query(String.class).list();

        assertThat(keys).containsExactly("sample:01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b");
    }

    @Test
    void countsEachAccountSeparately() {
        limit.consume(ANA);
        limit.consume(ANA);

        assertThatCode(() -> limit.consume(BRUNO)).doesNotThrowAnyException();
    }

    @Test
    void countsEachPrefixSeparately() {
        var other = new AccountRateLimit(rateLimitBuckets, "other:", CAPACITY, PERIOD);
        limit.consume(ANA);
        limit.consume(ANA);

        assertThatCode(() -> other.consume(ANA)).doesNotThrowAnyException();
    }

    /** Falha fechada (docs/adr/0006): sem o banco, a chamada não passa sem contar. */
    @Test
    void rejectsWhenTheStoreIsDown() {
        var store = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unreachable");
        var unreachable = new AccountRateLimit(bucketsOn(store), PREFIX, CAPACITY, PERIOD);

        assertThatThrownBy(() -> unreachable.consume(ANA)).isInstanceOf(RateLimitUnavailableException.class);
    }

    /** O Retry-After documentado tem teto de um dia; um período maior o quebraria. */
    @Test
    void acceptsAPeriodOfExactlyOneDay() {
        assertThatCode(() -> new AccountRateLimit(rateLimitBuckets, PREFIX, 1, Duration.ofDays(1)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void refusesACapacityThatIsNotPositive(int capacity) {
        assertThatThrownBy(() -> new AccountRateLimit(rateLimitBuckets, PREFIX, capacity, PERIOD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "P1DT1S", "P2D"})
    void refusesAPeriodOutsideZeroToOneDay(String period) {
        assertThatThrownBy(() -> new AccountRateLimit(rateLimitBuckets, PREFIX, CAPACITY, Duration.parse(period)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
    }

    private static ProxyManager<String> bucketsOn(DataSource dataSource) {
        return Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table("rate_limit_bucket")
                .build();
    }

}
