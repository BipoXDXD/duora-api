package bipo.tech.duoraapi.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import io.micrometer.tracing.Tracer;

@Configuration(proxyBeanMethods = false)
class RequestCorrelationConfiguration {

    /**
     * Logo depois do {@code ServerHttpObservationFilter} do Boot ({@code HIGHEST_PRECEDENCE + 1}), que
     * abre o span da requisição, e bem antes da cadeia do Spring Security.
     */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    @Bean
    FilterRegistrationBean<RequestIdResponseFilter> requestIdResponseFilter(Tracer tracer) {
        var registration = new FilterRegistrationBean<>(new RequestIdResponseFilter(tracer));
        registration.setOrder(ORDER);
        return registration;
    }

}
