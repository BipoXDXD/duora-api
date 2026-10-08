package bipo.tech.duoraapi.matching.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.distributed.proxy.ProxyManager;

import bipo.tech.duoraapi.config.AccountRateLimit;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RoundRateLimitProperties.class)
class RoundRateLimitConfiguration {

    static final String BEAN_NAME = "roundRateLimit";

    /** Separa estes buckets dos de outros limites na mesma tabela. */
    static final String KEY_PREFIX = "round:";

    @Bean(BEAN_NAME)
    AccountRateLimit roundRateLimit(ProxyManager<String> rateLimitBuckets, RoundRateLimitProperties properties) {
        return new AccountRateLimit(rateLimitBuckets, KEY_PREFIX, properties.capacity(), properties.period());
    }

}
