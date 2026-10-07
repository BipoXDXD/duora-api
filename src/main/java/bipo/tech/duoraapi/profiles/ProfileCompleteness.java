package bipo.tech.duoraapi.profiles;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.profiles.domain.ProfileRepository;

/**
 * API publicada do módulo profiles para os outros módulos (docs/adr/0011): se a pessoa já pode
 * participar, sem expor o perfil. Os critérios ficam aqui, num lugar só.
 */
@Service
public class ProfileCompleteness {

    private final ProfileRepository profiles;

    public ProfileCompleteness(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    /**
     * Nome, região e data de nascimento de quem tem 18 anos completos em {@code now}, a mesma regra do
     * {@code complete} do perfil. Conta sem perfil gravado tem o perfil vazio, que nunca está completo.
     */
    @Transactional(readOnly = true)
    public boolean isComplete(AccountId account, Instant now) {
        return profiles.findById(account.value())
                .map(profile -> profile.isComplete(now))
                .orElse(false);
    }

}
