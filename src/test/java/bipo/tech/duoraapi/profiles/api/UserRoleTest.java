package bipo.tech.duoraapi.profiles.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.TestingAuthenticationToken;

class UserRoleTest {

    @Test
    void noAuthoritiesGiveNoRoles() {
        assertThat(UserRole.grantedTo(authenticationWith())).isEmpty();
    }

    @Test
    void adminAuthorityGivesTheAdminRole() {
        assertThat(UserRole.grantedTo(authenticationWith("ROLE_ADMIN"))).containsExactly(UserRole.ADMIN);
    }

    @Test
    void repeatedAuthorityGivesTheRoleOnce() {
        assertThat(UserRole.grantedTo(authenticationWith("ROLE_ADMIN", "ROLE_ADMIN"))).containsExactly(UserRole.ADMIN);
    }

    @Test
    void knownRoleSurvivesAmongUnknownOnes() {
        assertThat(UserRole.grantedTo(authenticationWith("ROLE_MODERATOR", "ROLE_ADMIN"))).containsExactly(UserRole.ADMIN);
    }

    /** Só vale a authority escrita exatamente como o Spring a concede: prefixo, maiúsculas e nada mais. */
    @ParameterizedTest
    @ValueSource(strings = {"ROLE_MODERATOR", "ADMIN", "ROLE_admin", "ROLE_ADMIN ", "ROLE_ROLE_ADMIN", "SCOPE_ADMIN",
            "SCOPE_access_as_user"})
    void anyOtherAuthorityGivesNoRole(String authority) {
        assertThat(UserRole.grantedTo(authenticationWith(authority))).isEmpty();
    }

    private static TestingAuthenticationToken authenticationWith(String... authorities) {
        return new TestingAuthenticationToken("user", "credentials", authorities);
    }

}
