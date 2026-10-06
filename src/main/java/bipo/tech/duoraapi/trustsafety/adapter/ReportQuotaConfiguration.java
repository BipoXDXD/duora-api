package bipo.tech.duoraapi.trustsafety.adapter;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.bucket4j.distributed.proxy.ProxyManager;

import bipo.tech.duoraapi.trustsafety.application.ReportQuota;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReportRateLimitProperties.class)
class ReportQuotaConfiguration {

    @Bean
    ReportQuota reportQuota(ProxyManager<String> rateLimitBuckets, ReportRateLimitProperties properties) {
        return new BucketReportQuota(rateLimitBuckets, properties.capacity(), properties.period());
    }

}
