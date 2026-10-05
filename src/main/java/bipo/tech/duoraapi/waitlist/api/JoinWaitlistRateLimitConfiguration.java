package bipo.tech.duoraapi.waitlist.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JoinWaitlistRateLimitProperties.class)
class JoinWaitlistRateLimitConfiguration {

    @Bean
    JoinWaitlistRateLimitFilter joinWaitlistRateLimitFilter(JoinWaitlistRateLimitProperties properties) {
        return new JoinWaitlistRateLimitFilter(properties.capacity(), properties.period());
    }

}
