package bipo.tech.duoraapi;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A identidade externa que os testes usam ao montar o token ou a sessão de quem chama. O iss e o oid formam a
 * conta (docs/adr/0011): o mesmo emissor em todos os testes faz "oid-ana" ser a mesma conta pelo bearer e
 * pela sessão web.
 */
public final class TestIdentities {

    public static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";

    private static final SimpleGrantedAuthority ADMIN_AUTHORITY = new SimpleGrantedAuthority("ROLE_ADMIN");

    private TestIdentities() {
    }

    /**
     * Quem chama com bearer token, com o oid dado e sem papéis. O jwt() pula a validação do token, que está em
     * BearerTokenValidationIT.
     */
    public static RequestPostProcessor bearer(String objectId) {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", objectId));
    }

    /**
     * Quem chama com bearer token que traz o nome de exibição do Entra (claim name), sem papéis. O nome é o que
     * o /api/me devolve e o que o log nunca pode mostrar.
     */
    public static RequestPostProcessor bearer(String objectId, String displayName) {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", objectId).claim("name", displayName));
    }

    /** Quem chama pela sessão web (BFF), com o oid dado e sem papéis. O oidcLogin() também pula a validação. */
    public static RequestPostProcessor webSession(String objectId) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).claim("oid", objectId));
    }

    /** O ADMIN com bearer token, com o oid dado. */
    public static RequestPostProcessor adminBearer(String objectId) {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", objectId)).authorities(ADMIN_AUTHORITY);
    }

    /** O ADMIN pela sessão web, com o oid dado. */
    public static RequestPostProcessor adminWebSession(String objectId) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).claim("oid", objectId))
                .authorities(ADMIN_AUTHORITY);
    }

    /**
     * Um ADMIN com bearer token sem emissor nem oid: serve às rotas que só olham o papel e nunca abrem conta.
     */
    public static RequestPostProcessor adminWithoutIdentity() {
        return jwt().authorities(ADMIN_AUTHORITY);
    }

}
