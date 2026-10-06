package bipo.tech.duoraapi.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import io.micrometer.tracing.Tracer;

/**
 * Os dois filtros ficam logo depois do {@code ServerHttpObservationFilter} do Boot
 * ({@code HIGHEST_PRECEDENCE + 1}), que abre o trace da requisição, e bem antes da cadeia do Spring
 * Security: o que eles fazem precisa do trace aberto e vale também para as recusas da segurança.
 */
@Configuration(proxyBeanMethods = false)
class RequestCorrelationConfiguration {

    private static final int FIRST_AFTER_OBSERVATION = Ordered.HIGHEST_PRECEDENCE + 2;

    @Bean
    FilterRegistrationBean<RequestIdResponseFilter> requestIdResponseFilter(Tracer tracer) {
        var registration = new FilterRegistrationBean<>(new RequestIdResponseFilter(tracer));
        registration.setOrder(FIRST_AFTER_OBSERVATION);
        return registration;
    }

    @Bean
    FilterRegistrationBean<UnhandledExceptionLoggingFilter> unhandledExceptionLoggingFilter() {
        var registration = new FilterRegistrationBean<>(new UnhandledExceptionLoggingFilter());
        registration.setOrder(FIRST_AFTER_OBSERVATION + 1);
        return registration;
    }

}
