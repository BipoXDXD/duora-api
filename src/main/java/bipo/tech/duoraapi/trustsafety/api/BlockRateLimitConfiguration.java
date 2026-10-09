package bipo.tech.duoraapi.trustsafety.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.distributed.proxy.ProxyManager;

import bipo.tech.duoraapi.config.AccountRateLimit;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BlockRateLimitProperties.class)
class BlockRateLimitConfiguration {

    static final String BEAN_NAME = "blockRateLimit";

    /** Separa estes buckets dos de outros limites na mesma tabela. */
    static final String KEY_PREFIX = "block:";

    @Bean(BEAN_NAME)
    AccountRateLimit blockRateLimit(ProxyManager<String> rateLimitBuckets, BlockRateLimitProperties properties) {
        return new AccountRateLimit(rateLimitBuckets, KEY_PREFIX, properties.capacity(), properties.period());
    }

}
