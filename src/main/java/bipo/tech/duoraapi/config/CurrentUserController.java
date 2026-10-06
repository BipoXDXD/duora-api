package bipo.tech.duoraapi.config;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quem está logado, para o front web saber se há sessão e o que exibir (docs/adr/0002). O principal
 * é o OidcUser da sessão web ou o Jwt da porta bearer; os dois expõem as claims do Entra.
 */
@RestController
class CurrentUserController {

    @GetMapping("/api/me")
    CurrentUserResponse currentUser(@AuthenticationPrincipal ClaimAccessor user) {
        return new CurrentUserResponse(user.getClaimAsString(StandardClaimNames.NAME));
    }

}
