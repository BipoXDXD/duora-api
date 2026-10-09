package bipo.tech.duoraapi.config;

import static bipo.tech.duoraapi.config.ProblemDetailSecurityResponses.forbidden;
import static bipo.tech.duoraapi.config.ProblemDetailSecurityResponses.problemAccessDeniedHandler;
import static bipo.tech.duoraapi.config.ProblemDetailSecurityResponses.problemEntryPoint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import bipo.tech.duoraapi.identity.IdentityClaims;

/**
 * Duas portas de entrada, ambas negando por padrão, com as mesmas regras de rota:
 * <ul>
 * <li>bearer (docs/adr/0001): requisições com {@code Authorization: Bearer}, como o app mobile;
 * <li>web (docs/adr/0002): o resto. O Spring faz o login no Entra External ID e o navegador só
 * recebe o cookie de sessão; mutações exigem o token CSRF.
 * </ul>
 * Sem credencial válida, 401; sem o papel exigido ou sem o token CSRF, 403. Os dois em ProblemDetail.
 */
@Configuration(proxyBeanMethods = false)
@Import(WebLoginConfiguration.class)
public class SecurityConfiguration {

    private static final String BEARER_PREFIX = "Bearer ";

    private static final RequestMatcher BEARER_REQUEST = request -> {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization != null
                && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
    };

    private static final RequestMatcher JOIN_WAITLIST =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/waitlist");

    private static final RequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");

    /** Só ADMIN; a documentação da API também mora aqui (docs/adr/0012). */
    static final String ADMIN_ROUTES = "/api/admin/**";

    @Bean
    @Order(1)
    SecurityFilterChain bearerTokenFilterChain(HttpSecurity http) {
        return http
                .securityMatcher(BEARER_REQUEST)
                .authorizeHttpRequests(SecurityConfiguration::authorizeRoutes)
                // Sem sessão nem cookie: não há o que o CSRF proteger.
                .csrf(csrf -> csrf.disable())
                // O logout padrão responderia 302 para /login?logout, antes mesmo de validar o token: sem
                // sessão a encerrar, a porta bearer não participa do logout (docs/adr/0001).
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problemEntryPoint(new BearerTokenAuthenticationEntryPoint()))
                        .accessDeniedHandler(problemAccessDeniedHandler(new BearerTokenAccessDeniedHandler())))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain webSessionFilterChain(HttpSecurity http,
            OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService,
            LogoutSuccessHandler entraLogoutSuccessHandler) {
        return http
                .authorizeHttpRequests(SecurityConfiguration::authorizeRoutes)
                .oauth2Login(login -> login
                        // Sem página de login própria: quem precisa entrar vai direto ao Entra.
                        .loginPage(WebLoginConfiguration.LOGIN_PATH)
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/?loginError"))
                .logout(logout -> logout.logoutSuccessHandler(entraLogoutSuccessHandler))
                // Token no cookie XSRF-TOKEN, devolvido pelo front no header X-XSRF-TOKEN. A inscrição na
                // waitlist é anônima: não há sessão de vítima a explorar, e o app mobile a chama sem token.
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers(JOIN_WAITLIST))
                // A API responde 401 em vez de redirecionar para o login; o front decide quando levar ao login.
                .exceptionHandling(errors -> errors
                        .defaultAuthenticationEntryPointFor(
                                problemEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)), API)
                        .accessDeniedHandler(problemAccessDeniedHandler(forbidden())))
                .build();
    }

    private static void authorizeRoutes(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
        auth
                // O Tomcat encaminha exceções não tratadas para /error. Recusar esse encaminhamento
                // trocaria o 5xx por um 401. Só o encaminhamento: GET /error direto continua fechado.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                // Probes do Container Apps: a plataforma chama sem credencial e só lê o estado (docs/adr/0014).
                .requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .requestMatchers(JOIN_WAITLIST).permitAll()
                .requestMatchers(ADMIN_ROUTES).hasRole("ADMIN")
                .anyRequest().authenticated();
    }

    /**
     * O token precisa ter sido emitido para esta API, e não para outro app do tenant (um ID token
     * do front, por exemplo).
     */
    @Bean
    OAuth2TokenValidator<Jwt> audienceValidator(@Value("${duora.auth.audience}") String audience) {
        Assert.hasText(audience, "duora.auth.audience (DUORA_AUTH_AUDIENCE) must not be blank");
        return new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                audiences -> audiences != null && audiences.contains(audience));
    }

    /**
     * Soma-se aos validadores padrão (issuer, tempo). O de tempo só confere exp quando
     * a claim existe: sem este, um token sem expiração valeria para sempre.
     */
    @Bean
    OAuth2TokenValidator<Jwt> expirationRequiredValidator() {
        return new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull);
    }

    /** Sem oid não há como saber quem é o usuário: ele é a identidade nas duas portas de entrada. */
    @Bean
    OAuth2TokenValidator<Jwt> objectIdRequiredValidator() {
        return new JwtClaimValidator<String>(IdentityClaims.OBJECT_ID, StringUtils::hasText);
    }

}
