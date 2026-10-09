package bipo.tech.duoraapi;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A identidade externa que os testes usam ao montar o token ou a sessão de quem chama. O iss e o oid formam a
 * conta (docs/adr/0011): o mesmo emissor em todos os testes faz "oid-ana" ser a mesma conta pelo bearer e
 * pela sessão web.
 */
public final class TestIdentities {

    public static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";

    private TestIdentities() {
    }

    /**
     * Quem chama com bearer token, com o oid dado e sem papéis. O jwt() pula a validação do token, que está em
     * BearerTokenValidationIT.
     */
    public static RequestPostProcessor bearer(String objectId) {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", objectId));
    }

}
