package bipo.tech.duoraapi.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Só atrás do proxy a faixa é obrigatória: em desenvolvimento e nos testes a conexão vem direto do
 * cliente, e o padrão do Boot basta.
 */
@Configuration(proxyBeanMethods = false)
@Profile("behind-proxy")
@EnableConfigurationProperties(TrustedProxyProperties.class)
class TrustedProxyConfiguration {
}
