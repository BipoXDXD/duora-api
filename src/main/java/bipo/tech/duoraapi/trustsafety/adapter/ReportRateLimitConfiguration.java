package bipo.tech.duoraapi.trustsafety.adapter;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.distributed.proxy.ProxyManager;

import bipo.tech.duoraapi.config.AccountRateLimit;
import bipo.tech.duoraapi.trustsafety.application.ReportService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReportRateLimitProperties.class)
class ReportRateLimitConfiguration {

    /** Separa estes buckets dos de outros limites na mesma tabela. */
    static final String KEY_PREFIX = "report:";

    @Bean(ReportService.RATE_LIMIT_BEAN)
    AccountRateLimit reportRateLimit(ProxyManager<String> rateLimitBuckets, ReportRateLimitProperties properties) {
        return new AccountRateLimit(rateLimitBuckets, KEY_PREFIX, properties.capacity(), properties.period());
    }

}
