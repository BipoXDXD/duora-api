package bipo.tech.duoraapi.config;

import static bipo.tech.duoraapi.RateLimitTestSupport.clearBuckets;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.MeterRegistry;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Este teste usa o relógio do sistema de propósito. O Bucket4j grava {@code expires_at} com
 * {@code System.currentTimeMillis()}, que o TestClock (o bean {@code Clock} da API) não alcança. Por isso os
 * instantes esperados vêm de uma janela medida em volta da chamada, e não de um valor fixo.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExpiredRateLimitBucketCleanerIT {

    private static final BucketConfiguration ONE_PER_MINUTE = BucketConfiguration.builder()
            .addLimit(limit -> limit.capacity(1).refillGreedy(1, Duration.ofMinutes(1)))
            .build();

    private static final long TWO_MINUTES_IN_MILLIS = Duration.ofMinutes(2).toMillis();

    @Autowired
    private ExpiredRateLimitBucketCleaner cleaner;

    @Autowired
    private ProxyManager<String> rateLimitBuckets;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanTable() {
        clearBuckets(jdbcClient);
    }

    @Test
    void storesExpirationAtFullRefillPlusMargin() {
        long before = System.currentTimeMillis();
        rateLimitBuckets.getProxy("test:client", () -> ONE_PER_MINUTE).tryConsume(1);
        long after = System.currentTimeMillis();

        var expiresAt = jdbcClient.sql("select expires_at from rate_limit_bucket where id = 'test:client'")
                .query(Long.class)
                .single();
        // Um minuto para repor a ficha consumida + um minuto de folga.
        assertThat(expiresAt).isBetween(before + TWO_MINUTES_IN_MILLIS, after + TWO_MINUTES_IN_MILLIS);
    }

    @Test
    void removesExpiredBucket() {
        insertBucketsExpiredAt(1, oneSecondAgo());

        cleaner.removeExpiredBuckets();

        assertThat(storedKeys()).isZero();
    }

    @Test
    void keepsBucketStillRefilling() {
        rateLimitBuckets.getProxy("test:current-client", () -> ONE_PER_MINUTE).tryConsume(1);

        cleaner.removeExpiredBuckets();

        assertThat(storedKeys()).isOne();
    }

    @Test
    void keepsRemovingUntilNoFullBatchIsLeft() {
        insertBucketsExpiredAt(ExpiredRateLimitBucketCleaner.BATCH_SIZE + 1, oneSecondAgo());

        cleaner.removeExpiredBuckets();

        assertThat(storedKeys()).isZero();
    }

    @Test
    void reportsStoredBucketCount() {
        rateLimitBuckets.getProxy("test:a", () -> ONE_PER_MINUTE).tryConsume(1);
        rateLimitBuckets.getProxy("test:b", () -> ONE_PER_MINUTE).tryConsume(1);

        var gauge = meterRegistry.get(RateLimitConfiguration.BUCKET_COUNT_METRIC).gauge();

        assertThat(gauge.value()).isEqualTo(2.0);
    }

    private long storedKeys() {
        return jdbcClient.sql("select count(*) from rate_limit_bucket").query(Long.class).single();
    }

    private static long oneSecondAgo() {
        return System.currentTimeMillis() - Duration.ofSeconds(1).toMillis();
    }

    private void insertBucketsExpiredAt(int count, long expiresAtMillis) {
        jdbcClient.sql("""
                insert into rate_limit_bucket (id, state, expires_at)
                select 'test:expired-' || n, null, ? from generate_series(1, ?) as n
                """).params(expiresAtMillis, count).update();
    }

}
