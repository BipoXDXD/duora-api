package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
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

import com.zaxxer.hikari.HikariDataSource;

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

    @Autowired
    private HikariDataSource applicationPool;

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

    /**
     * Numa rajada, o comando espera conexão na fila do pool junto com as transações que esperam lock, e o
     * Bucket4j conta essa espera no teto (docs/adr/0016, medição de 2026-10-08). Uma fila de 1,5 s, que com
     * o teto antigo de 1 s virava 503, tem de ser absorvida.
     */
    @Test
    void waitsForAPoolThatIsBusyForLessThanTheTimeout() throws SQLException {
        try (var pool = singleConnectionPool()) {
            var limitOnBusyPool = new AccountRateLimit(RateLimitConfiguration.bucketsOn(pool), PREFIX, CAPACITY, PERIOD);
            holdTheOnlyConnection(pool, Duration.ofMillis(1500));

            assertThatCode(() -> limitOnBusyPool.consume(ANA)).doesNotThrowAnyException();
        }
    }

    /** A fila do pool acima do teto continua falhando fechada: a chamada não passa sem contar. */
    @Test
    void rejectsWhenThePoolStaysBusyBeyondTheTimeout() throws SQLException {
        try (var pool = singleConnectionPool()) {
            var limitOnBusyPool = new AccountRateLimit(RateLimitConfiguration.bucketsOn(pool), PREFIX, CAPACITY, PERIOD);
            holdTheOnlyConnection(pool, RateLimitConfiguration.REQUEST_TIMEOUT.plusMillis(500));

            assertThatThrownBy(() -> limitOnBusyPool.consume(ANA)).isInstanceOf(RateLimitUnavailableException.class);
        }
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

    private HikariDataSource singleConnectionPool() {
        var pool = new HikariDataSource();
        pool.setJdbcUrl(applicationPool.getJdbcUrl());
        pool.setUsername(applicationPool.getUsername());
        pool.setPassword(applicationPool.getPassword());
        pool.setMaximumPoolSize(1);
        pool.setPoolName("busy-rate-limit-test");
        return pool;
    }

    /**
     * Pega a única conexão do pool e a devolve depois de {@code duration}, como uma transação que espera o
     * lock do evento. O que se testa é um teto de tempo, então a espera é real: não há evento a aguardar.
     */
    private static void holdTheOnlyConnection(HikariDataSource pool, Duration duration) throws SQLException {
        Connection held = pool.getConnection();
        Thread.ofVirtual().start(() -> {
            try (held) {
                Thread.sleep(duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (SQLException e) {
                throw new IllegalStateException("could not give the held connection back", e);
            }
        });
    }

    private static ProxyManager<String> bucketsOn(DataSource dataSource) {
        return Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table("rate_limit_bucket")
                .build();
    }

}
