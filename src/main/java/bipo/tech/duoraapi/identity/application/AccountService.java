package bipo.tech.duoraapi.identity.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.identity.domain.AccountRepository;
import bipo.tech.duoraapi.identity.domain.ExternalIdentity;

@Service
public class AccountService {

    private final AccountRepository repository;
    private final Clock clock;

    public AccountService(AccountRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * A conta da identidade, aberta agora se ainda não existir. Seguro com acessos simultâneos: o insert
     * espera quem chegou antes, e a segunda leitura (READ COMMITTED, um snapshot por comando) já vê a
     * conta que essa transação gravou.
     */
    @Transactional
    public AccountId findOrOpenAccount(ExternalIdentity identity) {
        Optional<UUID> existing = repository.findIdByExternalIdentity(identity.issuer(), identity.subject());
        if (existing.isPresent()) {
            return new AccountId(existing.get());
        }
        repository.insertIfAbsent(identity.issuer(), identity.subject(), clock.instant());
        return repository.findIdByExternalIdentity(identity.issuer(), identity.subject())
                .map(AccountId::new)
                .orElseThrow(() -> new IllegalStateException("account not found right after being opened"));
    }

}
