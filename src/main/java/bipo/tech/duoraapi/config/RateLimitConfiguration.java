package bipo.tech.duoraapi.config;

import static io.github.bucket4j.distributed.ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax;

import java.time.Duration;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import io.github.bucket4j.postgresql.PostgreSQLSelectForUpdateBasedProxyManager;

/**
 * Buckets de rate limit no PostgreSQL (docs/adr/0006): o limite vale para o conjunto de réplicas, e
 * não para cada uma. Cada limite usa um prefixo próprio na chave.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RateLimitConfiguration {

    static final String TABLE = "rate_limit_bucket";
    static final String BUCKET_COUNT_METRIC = "duora.rate-limit.buckets";

    /** Folga depois da reposição completa, para não apagar um bucket que acabou de ser usado. */
    private static final Duration KEEP_AFTER_FULL_REFILL = Duration.ofMinutes(1);

    /**
     * Teto de cada comando no banco (o Bucket4j o aplica como query timeout, em segundos inteiros).
     * Sem ele, requisições do mesmo cliente esperariam o lock da linha sem limite, cada uma segurando
     * uma conexão do pool.
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    PostgreSQLSelectForUpdateBasedProxyManager<String> rateLimitBuckets(DataSource dataSource) {
        return Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table(TABLE)
                .expirationAfterWrite(basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_FULL_REFILL))
                .requestTimeout(REQUEST_TIMEOUT)
                .build();
    }

    @Bean
    ExpiredRateLimitBucketCleaner expiredRateLimitBucketCleaner(
            PostgreSQLSelectForUpdateBasedProxyManager<String> rateLimitBuckets) {
        return new ExpiredRateLimitBucketCleaner(rateLimitBuckets);
    }

    /** Tamanho da tabela: se a limpeza parar, a métrica sobe sem parar. */
    @Bean
    MeterBinder rateLimitBucketCount(JdbcClient jdbcClient) {
        return registry -> Gauge
                .builder(BUCKET_COUNT_METRIC, () -> jdbcClient.sql("select count(*) from " + TABLE).query(Long.class).single())
                .description("Rate limit buckets stored, expired or not")
                .register(registry);
    }

}
