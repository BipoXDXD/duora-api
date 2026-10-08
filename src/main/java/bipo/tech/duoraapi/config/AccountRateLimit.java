package bipo.tech.duoraapi.config;

import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.BucketExceptions.BucketExecutionException;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Limite de chamadas por conta de uma operação, num bucket do Bucket4j no PostgreSQL (docs/adr/0006): vale
 * para o conjunto de réplicas. A chave é "prefixo + conta", e cada operação tem o seu prefixo na tabela
 * compartilhada. Falha fechada: sem contar, a chamada não passa.
 */
public final class AccountRateLimit {

    /** Teto do Retry-After documentado nas operações: o limite se repõe em, no máximo, um dia. */
    public static final String MAX_RETRY_AFTER_SECONDS = "86400";

    /** Retry-After do 503 de quando o limite não pôde ser contado. */
    public static final String UNAVAILABLE_RETRY_AFTER_SECONDS = "1";

    static final Duration MAX_PERIOD = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(AccountRateLimit.class);

    private final ProxyManager<String> buckets;
    private final String keyPrefix;
    private final BucketConfiguration limit;

    /**
     * @param keyPrefix identifica o limite na tabela, como {@code "registration:"}
     * @param capacity chamadas por {@code period}, repostas aos poucos (greedy) ao longo dele
     * @throws IllegalArgumentException se a capacidade não é positiva ou o período não está entre zero
     *         (exclusive) e um dia, o que quebraria o teto do Retry-After documentado
     */
    public AccountRateLimit(ProxyManager<String> buckets, String keyPrefix, int capacity, Duration period) {
        this.buckets = Objects.requireNonNull(buckets, "buckets");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        if (capacity <= 0) {
            throw new IllegalArgumentException("rate limit capacity must be positive: " + keyPrefix);
        }
        if (period.isNegative() || period.isZero() || period.compareTo(MAX_PERIOD) > 0) {
            throw new IllegalArgumentException("rate limit period must be positive and at most one day: " + keyPrefix);
        }
        this.limit = BucketConfiguration.builder()
                .addLimit(bandwidth -> bandwidth.capacity(capacity).refillGreedy(capacity, period))
                .build();
    }

    /**
     * Gasta uma chamada do limite de {@code account}.
     *
     * @throws RateLimitExceededException se o limite acabou
     * @throws RateLimitUnavailableException se não deu para contar
     */
    public void consume(AccountId account) {
        ConsumptionProbe probe;
        try {
            probe = buckets.getProxy(keyPrefix + account.value(), () -> limit).tryConsumeAndReturnRemaining(1);
        } catch (BucketExecutionException e) {
            log.error("Rate limit store failed for {}; rejecting the call", keyPrefix, e);
            throw new RateLimitUnavailableException(e);
        }
        if (!probe.isConsumed()) {
            throw new RateLimitExceededException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
    }

}
