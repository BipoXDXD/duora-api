package bipo.tech.duoraapi.events.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.events.domain.Event;
import bipo.tech.duoraapi.events.domain.EventRepository;
import bipo.tech.duoraapi.events.domain.RegisteredEvent;
import bipo.tech.duoraapi.events.domain.Registration;
import bipo.tech.duoraapi.events.domain.RegistrationRepository;
import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.profiles.ProfileCompleteness;

/**
 * A inscrição da própria pessoa num evento. Quem chama passa a conta autenticada: não há como agir
 * sobre a inscrição de outra conta.
 */
@Service
public class RegistrationService {

    private final EventRepository events;
    private final RegistrationRepository registrations;
    private final ProfileCompleteness profiles;
    private final Clock clock;

    public RegistrationService(EventRepository events, RegistrationRepository registrations,
            ProfileCompleteness profiles, Clock clock) {
        this.events = events;
        this.registrations = registrations;
        this.profiles = profiles;
        this.clock = clock;
    }

    /**
     * Inscreve, ou devolve a inscrição que já existe. As inscrições de um evento entram uma por vez: a
     * linha do evento fica travada da contagem ao commit, então duas pessoas não ocupam a mesma última
     * vaga (docs/adr/0016).
     *
     * @throws EventNotFoundException se o evento não existe ou é rascunho
     * @throws IncompleteProfileException se o perfil não está completo
     * @throws bipo.tech.duoraapi.events.domain.EventStateConflictException se cancelado, começado ou lotado
     */
    @Transactional
    public RegistrationOutcome register(UUID eventId, AccountId account) {
        Instant now = clock.instant();
        registrations.limitLockWait();
        Event event = events.findByIdForUpdate(eventId)
                .filter(Event::isVisibleToParticipants)
                .orElseThrow(EventNotFoundException::new);
        Optional<Registration> existing = registrations.find(eventId, account);
        if (existing.isPresent()) {
            return new RegistrationOutcome(RegistrationView.of(existing.get()), false);
        }
        if (!profiles.isComplete(account, now)) {
            throw new IncompleteProfileException();
        }
        event.ensureAcceptsRegistration(registrations.countByEvent(eventId), now);
        var registration = new Registration(eventId, account, now.truncatedTo(ChronoUnit.MICROS));
        registrations.insert(registration);
        return new RegistrationOutcome(RegistrationView.of(registration), true);
    }

    @Transactional(readOnly = true)
    public RegistrationView find(UUID eventId, AccountId account) {
        return registrations.find(eventId, account)
                .map(RegistrationView::of)
                .orElseThrow(RegistrationNotFoundException::new);
    }

    /**
     * Cancela a inscrição até o evento começar. Sem inscrição, não há nada a fazer, e isso também é
     * sucesso.
     *
     * @throws EventNotFoundException se o evento não existe ou é rascunho
     * @throws bipo.tech.duoraapi.events.domain.EventStateConflictException se o evento já começou
     */
    @Transactional
    public void unregister(UUID eventId, AccountId account) {
        Event event = events.findById(eventId)
                .filter(Event::isVisibleToParticipants)
                .orElseThrow(EventNotFoundException::new);
        if (registrations.find(eventId, account).isEmpty()) {
            return;
        }
        event.ensureAllowsLeaving(clock.instant());
        registrations.delete(eventId, account);
    }

    /** A primeira página das inscrições em eventos que ainda não acabaram, pelo início do evento. */
    @Transactional(readOnly = true)
    public ResultPage<RegisteredEvent> firstOf(AccountId account, int pageSize) {
        List<RegisteredEvent> fetched = registrations.findCurrentOf(account, clock.instant(), pageSize + 1);
        return ResultPage.fromOneMoreThan(pageSize, fetched, RegistrationService::cursorOf);
    }

    /** A página seguinte à que terminou em {@code after}. */
    @Transactional(readOnly = true)
    public ResultPage<RegisteredEvent> ofAfter(AccountId account, PageCursor after, int pageSize) {
        List<RegisteredEvent> fetched = registrations.findCurrentOfAfter(account, clock.instant(), after.startsAt(),
                after.eventId(), pageSize + 1);
        return ResultPage.fromOneMoreThan(pageSize, fetched, RegistrationService::cursorOf);
    }

    private static PageCursor cursorOf(RegisteredEvent registration) {
        return new PageCursor(registration.startsAt(), registration.eventId());
    }

}
