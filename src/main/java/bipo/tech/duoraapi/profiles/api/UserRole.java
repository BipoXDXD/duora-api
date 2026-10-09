package bipo.tech.duoraapi.profiles.api;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Os papéis que o front conhece. Vêm das authorities que a segurança já concedeu, nunca do cliente: o
 * access token validado, nas duas portas de entrada (docs/adr/0002). Um papel que o Entra emita e esta lista
 * não conheça não sai pela API.
 */
enum UserRole {

    ADMIN;

    private static final String AUTHORITY_PREFIX = "ROLE_";

    /** Os papéis de quem fez a requisição, na ordem desta lista e sem repetição. */
    static List<UserRole> grantedTo(Authentication authentication) {
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return Stream.of(values())
                .filter(role -> authorities.contains(AUTHORITY_PREFIX + role.name()))
                .toList();
    }

}
