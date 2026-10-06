package bipo.tech.duoraapi.config;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.util.Assert;

import tools.jackson.databind.json.JsonMapper;

/**
 * Login do front web pelo próprio Spring (BFF, docs/adr/0002): o Spring é um cliente confidencial
 * do Entra External ID, e os tokens ficam no servidor.
 */
@Configuration(proxyBeanMethods = false)
public class WebLoginConfiguration {

    static final String REGISTRATION_ID = "entra";
    static final String LOGIN_PATH = "/oauth2/authorization/" + REGISTRATION_ID;
    static final String OBJECT_ID_CLAIM = "oid";

    /**
     * Montado aqui, e não pelas propriedades spring.security.oauth2.client.*, por dois motivos: @Value
     * falha na subida se faltar uma variável (o binder do Boot aceitaria o placeholder como texto), e a
     * descoberta automática recusaria o issuer do External ID, que usa outro host.
     */
    @Bean
    ClientRegistrationRepository clientRegistrationRepository(
            @Value("${duora.auth.authority}") String authority,
            @Value("${duora.auth.web.client-id}") String clientId,
            @Value("${duora.auth.web.client-secret}") String clientSecret,
            @Value("${duora.auth.audience}") String apiAudience,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        Assert.hasText(authority, "duora.auth.authority (DUORA_AUTH_AUTHORITY) must not be blank");
        Assert.hasText(clientId, "duora.auth.web.client-id (DUORA_AUTH_WEB_CLIENT_ID) must not be blank");
        Assert.hasText(clientSecret, "duora.auth.web.client-secret (DUORA_AUTH_WEB_CLIENT_SECRET) must not be blank");
        Assert.hasText(apiAudience, "duora.auth.audience (DUORA_AUTH_AUDIENCE) must not be blank");

        var registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                // profile traz o oid no ID token; o scope da API traz o access token de onde saem os papéis.
                .scope("openid", "profile", "api://" + apiAudience + "/access_as_user")
                .authorizationUri(authority + "/oauth2/v2.0/authorize")
                .tokenUri(authority + "/oauth2/v2.0/token")
                .jwkSetUri(jwkSetUri)
                .issuerUri(issuer)
                // sub sempre existe; a exigência do oid fica em oidcUserService, que a converte em falha de login.
                .userNameAttributeName(IdTokenClaimNames.SUB)
                // "issuer" faz o Spring conferir o iss do ID token (as chaves de assinatura do Entra são
                // compartilhadas entre tenants); "end_session_endpoint" permite sair também do Entra.
                .providerConfigurationMetadata(Map.of(
                        "issuer", issuer,
                        "end_session_endpoint", authority + "/oauth2/v2.0/logout"))
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    /**
     * Os papéis vêm do access token da API (claim roles), validado pelo mesmo JwtDecoder da porta
     * bearer: as duas portas de entrada concedem exatamente os mesmos papéis.
     */
    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(JwtDecoder apiTokenDecoder,
            JwtAuthenticationConverter apiAuthoritiesConverter) {
        var idTokenUserService = new OidcUserService();
        return request -> {
            OidcUser idTokenUser = idTokenUserService.loadUser(request);
            Jwt accessToken = decodeApiToken(apiTokenDecoder, request.getAccessToken());
            requireSameUser(idTokenUser, accessToken);
            Collection<GrantedAuthority> roles = apiAuthoritiesConverter.convert(accessToken).getAuthorities();
            return new DefaultOidcUser(roles, idTokenUser.getIdToken(), OBJECT_ID_CLAIM);
        };
    }

    private static Jwt decodeApiToken(JwtDecoder decoder, OAuth2AccessToken accessToken) {
        try {
            return decoder.decode(accessToken.getTokenValue());
        } catch (JwtException e) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "API access token rejected", null), e);
        }
    }

    private static void requireSameUser(OidcUser idTokenUser, Jwt accessToken) {
        String idTokenUserId = idTokenUser.getIdToken().getClaimAsString(OBJECT_ID_CLAIM);
        if (idTokenUserId == null || !Objects.equals(idTokenUserId, accessToken.getClaimAsString(OBJECT_ID_CLAIM))) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "ID token and access token belong to different users", null));
        }
    }

    /**
     * Encerra a sessão aqui e também no Entra, para o próximo login pedir credenciais de novo. O front
     * chama o logout por fetch, que não segue um 302 para outra origem: em vez do redirect, a resposta
     * é 200 com a URL de logout do Entra, e o front navega até ela.
     */
    @Bean
    LogoutSuccessHandler entraLogoutSuccessHandler(ClientRegistrationRepository clientRegistrations,
            JsonMapper jsonMapper) {
        var handler = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        handler.setRedirectStrategy((request, response, logoutUrl) -> {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            jsonMapper.writeValue(response.getOutputStream(), new LogoutResponse(logoutUrl));
        });
        return handler;
    }

    private record LogoutResponse(String logoutUrl) {
    }

}
