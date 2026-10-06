package bipo.tech.duoraapi.identity.api;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import bipo.tech.duoraapi.identity.application.AccountService;

@Configuration(proxyBeanMethods = false)
class CurrentAccountConfiguration implements WebMvcConfigurer {

    private final AccountService accounts;

    CurrentAccountConfiguration(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentAccountArgumentResolver(accounts));
    }

}
