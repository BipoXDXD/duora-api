package bipo.tech.duoraapi.trustsafety.adapter;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.BucketExceptions.BucketExecutionException;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.application.ReportQuota;
import bipo.tech.duoraapi.trustsafety.application.ReportQuotaExceededException;
import bipo.tech.duoraapi.trustsafety.application.ReportQuotaUnavailableException;

/**
 * Cota de denúncias por conta num bucket do Bucket4j no PostgreSQL (docs/adr/0006). A chave é a conta,
 * e não o IP: a denúncia exige login, e trocar de rede não renova a cota.
 */
class BucketReportQuota implements ReportQuota {

    private static final Logger log = LoggerFactory.getLogger(BucketReportQuota.class);

    /** Separa estes buckets dos de outros limites na mesma tabela. */
    static final String KEY_PREFIX = "report:";

    private final ProxyManager<String> buckets;
    private final BucketConfiguration limit;

    BucketReportQuota(ProxyManager<String> buckets, int capacity, Duration period) {
        this.buckets = buckets;
        this.limit = BucketConfiguration.builder()
                .addLimit(bandwidth -> bandwidth.capacity(capacity).refillGreedy(capacity, period))
                .build();
    }

    @Override
    public void consume(AccountId reporter) {
        ConsumptionProbe probe;
        try {
            probe = buckets.getProxy(KEY_PREFIX + reporter.value(), () -> limit).tryConsumeAndReturnRemaining(1);
        } catch (BucketExecutionException e) {
            log.error("Report quota store failed; rejecting the report", e);
            throw new ReportQuotaUnavailableException(e);
        }
        if (!probe.isConsumed()) {
            throw new ReportQuotaExceededException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
    }

}
