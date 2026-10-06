package bipo.tech.duoraapi.profiles.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.profiles.domain.Profile;
import bipo.tech.duoraapi.profiles.domain.ProfileChanges;
import bipo.tech.duoraapi.profiles.domain.ProfileRepository;

/** Perfil do próprio usuário. Quem chama passa a conta autenticada: não há acesso ao perfil de outra conta. */
@Service
public class ProfileService {

    private final ProfileRepository repository;
    private final Clock clock;

    public ProfileService(ProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Antes da primeira edição não há linha no banco, e o perfil é o vazio, na versão 0. */
    @Transactional(readOnly = true)
    public ProfileView profileOf(UUID accountId) {
        Profile profile = repository.findById(accountId).orElseGet(() -> Profile.emptyFor(accountId));
        return ProfileView.of(profile, clock.instant());
    }

    /**
     * Aplica a edição se o perfil ainda estiver na versão que o cliente leu. Uma edição simultânea que
     * passe pela mesma conferência perde no flush, pela versão otimista do JPA.
     *
     * @throws OutdatedProfileVersionException se outra edição foi gravada depois da leitura
     */
    @Transactional
    public ProfileView edit(UUID accountId, long readVersion, ProfileChanges changes) {
        Instant now = clock.instant();
        repository.insertEmptyIfAbsent(accountId);
        Profile profile = repository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("profile not found right after being ensured"));
        if (profile.version() != readVersion) {
            throw new OutdatedProfileVersionException();
        }
        profile.apply(changes, now);
        repository.flush();
        return ProfileView.of(profile, now);
    }

}
