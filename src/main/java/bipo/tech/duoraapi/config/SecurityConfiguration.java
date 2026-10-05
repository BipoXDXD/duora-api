package bipo.tech.duoraapi.config;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.Assert;

/**
 * Nega por padrão: só as rotas listadas aqui são públicas. As demais exigem um bearer token
 * válido do Entra External ID (docs/adr/0001); sem ele, 401, e sem o papel exigido, 403.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/waitlist").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // API sem sessão nem cookie de autenticação: não há o que o CSRF proteger.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
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

}
