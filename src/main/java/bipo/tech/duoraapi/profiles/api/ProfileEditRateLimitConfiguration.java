package bipo.tech.duoraapi.profiles.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.distributed.proxy.ProxyManager;

import bipo.tech.duoraapi.config.AccountRateLimit;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileEditRateLimitProperties.class)
class ProfileEditRateLimitConfiguration {

    static final String BEAN_NAME = "profileEditRateLimit";

    /** Separa estes buckets dos de outros limites na mesma tabela. */
    static final String KEY_PREFIX = "profile:";

    @Bean(BEAN_NAME)
    AccountRateLimit profileEditRateLimit(ProxyManager<String> rateLimitBuckets,
            ProfileEditRateLimitProperties properties) {
        return new AccountRateLimit(rateLimitBuckets, KEY_PREFIX, properties.capacity(), properties.period());
    }

}
