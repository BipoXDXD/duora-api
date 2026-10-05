package bipo.tech.duoraapi.waitlist.application;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.waitlist.domain.EmailAddress;
import bipo.tech.duoraapi.waitlist.domain.WaitlistEntryRepository;

@Service
public class WaitlistService {

    private final WaitlistEntryRepository repository;
    private final Clock clock;

    public WaitlistService(WaitlistEntryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Entrar de novo com o mesmo e-mail não tem efeito: a data original é mantida. */
    @Transactional
    public void join(EmailAddress email) {
        repository.insertIfAbsent(email.value(), clock.instant());
    }

    @Transactional(readOnly = true)
    public long countEntries() {
        return repository.count();
    }

}
