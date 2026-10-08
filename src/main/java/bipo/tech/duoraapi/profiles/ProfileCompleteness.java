package bipo.tech.duoraapi.profiles;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.profiles.domain.Profile;
import bipo.tech.duoraapi.profiles.domain.ProfileRepository;

/**
 * API publicada do módulo profiles para os outros módulos (docs/adr/0011): se a pessoa já pode
 * participar, ou o que falta, sem expor o perfil. Os critérios ficam aqui, num lugar só.
 */
@Service
public class ProfileCompleteness {

    private final ProfileRepository profiles;

    public ProfileCompleteness(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    /**
     * A mesma regra do {@code complete} do perfil, com a idade em {@code now}. Conta sem perfil gravado tem o
     * perfil vazio, que nunca está completo. O que falta só é dito à própria pessoa, que já conhece o próprio
     * perfil (docs/adr/0020).
     */
    @Transactional(readOnly = true)
    public Eligibility eligibilityOf(AccountId account, Instant now) {
        return profiles.findById(account.value())
                .map(profile -> eligibilityOf(profile, now))
                .orElse(Eligibility.PROFILE_INCOMPLETE);
    }

    private static Eligibility eligibilityOf(Profile profile, Instant now) {
        if (!profile.isFilledIn()) {
            return Eligibility.PROFILE_INCOMPLETE;
        }
        return profile.isComplete(now) ? Eligibility.ELIGIBLE : Eligibility.UNDERAGE;
    }

}
