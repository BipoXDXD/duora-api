package bipo.tech.duoraapi.waitlist.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.distributed.proxy.ProxyManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JoinWaitlistRateLimitProperties.class)
class JoinWaitlistRateLimitConfiguration {

    @Bean
    JoinWaitlistRateLimitFilter joinWaitlistRateLimitFilter(ProxyManager<String> rateLimitBuckets,
            JoinWaitlistRateLimitProperties properties) {
        return new JoinWaitlistRateLimitFilter(rateLimitBuckets, properties.capacity(), properties.period());
    }

}
