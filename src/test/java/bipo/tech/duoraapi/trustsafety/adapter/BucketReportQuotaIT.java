package bipo.tech.duoraapi.trustsafety.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import bipo.tech.duoraapi.trustsafety.application.ReportQuotaExceededException;
import bipo.tech.duoraapi.trustsafety.application.ReportQuotaUnavailableException;

/** A cota de denúncias com os buckets no PostgreSQL real, com capacidade pequena para chegar logo ao fim. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BucketReportQuotaIT {

    private static final int CAPACITY = 2;
    private static final Duration PERIOD = Duration.ofHours(1);
    private static final AccountId ANA = new AccountId(UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b"));
    private static final AccountId BRUNO = new AccountId(UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8c"));

    @Autowired
    private ProxyManager<String> rateLimitBuckets;

    @Autowired
    private JdbcClient jdbcClient;

    private BucketReportQuota quota;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("delete from rate_limit_bucket").update();
        quota = new BucketReportQuota(rateLimitBuckets, CAPACITY, PERIOD);
    }

    /** Reposição gradual: uma denúncia a cada PERIOD / CAPACITY = 30 min. */
    @Test
    void rejectsAboveCapacityWithTheTimeToTheNextReport() {
        quota.consume(ANA);
        quota.consume(ANA);

        assertThatThrownBy(() -> quota.consume(ANA))
                .isInstanceOfSatisfying(ReportQuotaExceededException.class, exceeded ->
                        assertThat(exceeded.retryAfter()).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(30)));
    }

    /** A tabela é compartilhada com outros limites: a chave é "<limite>:<conta>" (docs/adr/0006). */
    @Test
    void keepsTheBucketUnderTheReportKey() {
        quota.consume(ANA);

        var keys = jdbcClient.sql("select id from rate_limit_bucket").query(String.class).list();

        assertThat(keys).containsExactly("report:01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b");
    }

    @Test
    void countsEachAccountSeparately() {
        quota.consume(ANA);
        quota.consume(ANA);

        assertThatCode(() -> quota.consume(BRUNO)).doesNotThrowAnyException();
    }

    /** Falha fechada (docs/adr/0006): sem o banco, a denúncia não passa sem contar. */
    @Test
    void rejectsWhenTheStoreIsDown() {
        var store = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unreachable");
        var unreachable = new BucketReportQuota(bucketsOn(store), CAPACITY, PERIOD);

        assertThatThrownBy(() -> unreachable.consume(ANA)).isInstanceOf(ReportQuotaUnavailableException.class);
    }

    private static ProxyManager<String> bucketsOn(DataSource dataSource) {
        return Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table("rate_limit_bucket")
                .build();
    }

}
