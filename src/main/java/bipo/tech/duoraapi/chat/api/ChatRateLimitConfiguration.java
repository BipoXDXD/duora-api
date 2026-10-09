package bipo.tech.duoraapi.chat.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.distributed.proxy.ProxyManager;

import bipo.tech.duoraapi.config.AccountRateLimit;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatRateLimitProperties.class)
class ChatRateLimitConfiguration {

    static final String BEAN_NAME = "chatMessageRateLimit";

    /** Separa estes buckets dos de outros limites na mesma tabela. */
    static final String KEY_PREFIX = "chat:";

    @Bean(BEAN_NAME)
    AccountRateLimit chatMessageRateLimit(ProxyManager<String> rateLimitBuckets, ChatRateLimitProperties properties) {
        return new AccountRateLimit(rateLimitBuckets, KEY_PREFIX, properties.capacity(), properties.period());
    }

}
