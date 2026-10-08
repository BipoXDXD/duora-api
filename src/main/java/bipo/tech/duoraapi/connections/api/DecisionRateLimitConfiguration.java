package bipo.tech.duoraapi.connections.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.distributed.proxy.ProxyManager;

import bipo.tech.duoraapi.config.AccountRateLimit;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DecisionRateLimitProperties.class)
class DecisionRateLimitConfiguration {

    static final String BEAN_NAME = "decisionRateLimit";

    /** Separa estes buckets dos de outros limites na mesma tabela. */
    static final String KEY_PREFIX = "decision:";

    @Bean(BEAN_NAME)
    AccountRateLimit decisionRateLimit(ProxyManager<String> rateLimitBuckets,
            DecisionRateLimitProperties properties) {
        return new AccountRateLimit(rateLimitBuckets, KEY_PREFIX, properties.capacity(), properties.period());
    }

}
