package bipo.tech.duoraapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import io.github.bucket4j.distributed.proxy.ExpiredEntriesCleaner;

/**
 * Apaga os buckets que já se repuseram por inteiro: sem isso, a tabela cresce com cada IP que já
 * passou por aqui. Várias réplicas podem rodar ao mesmo tempo, porque o Bucket4j apaga com
 * {@code FOR UPDATE SKIP LOCKED}.
 */
final class ExpiredRateLimitBucketCleaner {

    private static final Logger log = LoggerFactory.getLogger(ExpiredRateLimitBucketCleaner.class);

    /** Lote por transação, para não segurar locks por muito tempo. */
    static final int BATCH_SIZE = 1_000;

    private final ExpiredEntriesCleaner buckets;

    ExpiredRateLimitBucketCleaner(ExpiredEntriesCleaner buckets) {
        this.buckets = buckets;
    }

    @Scheduled(fixedDelayString = "${duora.rate-limit.cleanup-interval}",
            initialDelayString = "${duora.rate-limit.cleanup-interval}")
    public void removeExpiredBuckets() {
        int removed;
        int total = 0;
        do {
            removed = buckets.removeExpired(BATCH_SIZE);
            total += removed;
        } while (removed == BATCH_SIZE);
        log.info("Removed {} expired rate limit buckets", total);
    }

}
