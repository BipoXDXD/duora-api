package bipo.tech.duoraapi.identity.api;

import java.util.List;

import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.identity.application.AccountService;

@Configuration(proxyBeanMethods = false)
class CurrentAccountConfiguration implements WebMvcConfigurer {

    /*
     * A conta vem do principal, e não do cliente: fora da spec OpenAPI (docs/adr/0012). Anotar o
     * parâmetro não serve, porque o resolver só atende AccountId sem anotação, e o springdoc só
     * oferece esta configuração global e estática.
     */
    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AccountId.class);
    }

    private final AccountService accounts;

    CurrentAccountConfiguration(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentAccountArgumentResolver(accounts));
    }

}
