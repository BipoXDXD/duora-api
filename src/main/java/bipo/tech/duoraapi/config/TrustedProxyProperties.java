package bipo.tech.duoraapi.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Confere a faixa de proxies que o Tomcat usa. Lê a mesma chave do Boot, em vez de uma própria, para
 * validar exatamente o valor que vale. Em branco, o Tomcat confiaria em qualquer conexão, e o
 * cliente escolheria o próprio IP pelo {@code X-Forwarded-For}.
 */
@Validated
@ConfigurationProperties("server.tomcat.remoteip")
record TrustedProxyProperties(@NotBlank String internalProxies) {
}
