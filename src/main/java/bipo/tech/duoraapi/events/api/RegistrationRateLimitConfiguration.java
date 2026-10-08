package bipo.tech.duoraapi.events.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.distributed.proxy.ProxyManager;

import bipo.tech.duoraapi.config.AccountRateLimit;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RegistrationRateLimitProperties.class)
class RegistrationRateLimitConfiguration {

    static final String BEAN_NAME = "registrationRateLimit";

    /** Separa estes buckets dos de outros limites na mesma tabela. */
    static final String KEY_PREFIX = "registration:";

    @Bean(BEAN_NAME)
    AccountRateLimit registrationRateLimit(ProxyManager<String> rateLimitBuckets,
            RegistrationRateLimitProperties properties) {
        return new AccountRateLimit(rateLimitBuckets, KEY_PREFIX, properties.capacity(), properties.period());
    }

}
