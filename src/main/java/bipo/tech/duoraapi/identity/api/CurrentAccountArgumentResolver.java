package bipo.tech.duoraapi.identity.api;

import java.security.Principal;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.identity.IdentityClaims;
import bipo.tech.duoraapi.identity.application.AccountService;
import bipo.tech.duoraapi.identity.domain.ExternalIdentity;

/**
 * Entrega a conta de quem fez a requisição ao parâmetro {@link AccountId} sem anotação, abrindo-a no
 * primeiro acesso. O principal é o OidcUser da sessão web ou o Jwt da porta bearer: os dois trazem as
 * claims iss e oid, validadas na autenticação. Sem elas, é defeito de configuração, e a requisição falha.
 */
final class CurrentAccountArgumentResolver implements HandlerMethodArgumentResolver {

    private final AccountService accounts;

    CurrentAccountArgumentResolver(AccountService accounts) {
        this.accounts = accounts;
    }

    /** Com anotação (um @PathVariable, por exemplo), o id vem de outro lugar, e não é a conta de quem chama. */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(AccountId.class) && !parameter.hasParameterAnnotations();
    }

    @Override
    public AccountId resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Principal principal = webRequest.getUserPrincipal();
        if (!(principal instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof ClaimAccessor claims)) {
            throw new IllegalStateException("AccountId requires an authenticated principal with claims");
        }
        var identity = new ExternalIdentity(claims.getClaimAsString(JwtClaimNames.ISS),
                claims.getClaimAsString(IdentityClaims.OBJECT_ID));
        return accounts.findOrOpenAccount(identity);
    }

}
