package bipo.tech.duoraapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Cookie da sessão do front web (docs/adr/0002). Declarado aqui, e não por server.servlet.session.cookie.*,
 * porque o Boot só aplica essas propriedades com servidor embutido: em outro tipo de implantação (e nos
 * testes com MockMvc) o cookie sairia sem HttpOnly, Secure e SameSite.
 */
@Configuration(proxyBeanMethods = false)
class WebSessionConfiguration {

    /** __Host-: o navegador só aceita com Secure, sem Domain e com Path=/, o que impede subdomínio de sobrescrevê-lo. */
    static final String SESSION_COOKIE_NAME = "__Host-DUORA_SESSION";

    @Bean
    CookieSerializer sessionCookieSerializer() {
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName(SESSION_COOKIE_NAME);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(true);
        // Lax, e não Strict: o cookie precisa voltar no redirect do Entra para concluir o login.
        serializer.setSameSite("Lax");
        return new WellFormedSessionIdCookieSerializer(serializer);
    }

}
