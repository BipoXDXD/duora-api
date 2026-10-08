package bipo.tech.duoraapi.events.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.UuidGenerator;

import bipo.tech.duoraapi.FieldErrorCode;
import bipo.tech.duoraapi.RefusalReason;

/**
 * Um encontro com horário e vagas, criado como rascunho pelo ADMIN e publicado para as inscrições. O
 * ciclo guardado é rascunho → publicado → cancelado; "em andamento" e "encerrado" vêm do horário.
 *
 * <p>Não é {@code final} e tem construtor sem argumentos porque o JPA exige.
 */
@Entity
@Table(name = "event")
public class Event {

    /** Evento marcado para daqui a mais de um ano é quase sempre erro de digitação no ano. */
    public static final Duration MAX_LEAD = Duration.ofDays(365);

    private static final String ONLY_A_DRAFT_CAN_BE_PUBLISHED = "only a draft can be published";
    private static final String ENDED = "the event has already ended";

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String title;

    private String description;

    private Instant startsAt;

    private Instant endsAt;

    private int capacity;

    @Enumerated(EnumType.STRING)
    private EventStatus status;

    private Instant createdAt;

    /** Publicar e cancelar ao mesmo tempo: só uma das ações grava. */
    @Version
    private long version;

    protected Event() {
        // exigido pelo JPA
    }

    private Event(EventTitle title, EventDescription description, EventSchedule schedule, Capacity capacity,
            Instant createdAt) {
        this.title = title.value();
        this.description = description.value();
        this.startsAt = schedule.startsAt();
        this.endsAt = schedule.endsAt();
        this.capacity = capacity.places();
        this.status = EventStatus.DRAFT;
        this.createdAt = createdAt;
    }

    /** Um rascunho, que só o ADMIN vê, de um evento ainda por começar. */
    public static Event draft(EventTitle title, EventDescription description, EventSchedule schedule,
            Capacity capacity, Instant now) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(capacity, "capacity");
        if (!schedule.startsAt().isAfter(now)) {
            throw new InvalidEventException("startsAt", FieldErrorCode.BELOW_MINIMUM, "startsAt must be in the future");
        }
        if (schedule.startsAt().isAfter(now.plus(MAX_LEAD))) {
            throw new InvalidEventException("startsAt", FieldErrorCode.ABOVE_MAXIMUM,
                    "startsAt must be at most " + MAX_LEAD.toDays() + " days ahead");
        }
        return new Event(title, description, schedule, capacity, now);
    }

    public void publish(Instant now) {
        switch (status) {
            case PUBLISHED -> throw new EventStateConflictException(RefusalReason.EVENT_ALREADY_PUBLISHED,
                    ONLY_A_DRAFT_CAN_BE_PUBLISHED);
            case CANCELLED -> throw new EventStateConflictException(RefusalReason.EVENT_CANCELLED,
                    ONLY_A_DRAFT_CAN_BE_PUBLISHED);
            case DRAFT -> ensureNotStarted(now);
        }
        status = EventStatus.PUBLISHED;
    }

    /** Cancela até o fim, inclusive durante o evento; as inscrições continuam guardadas. */
    public void cancel(Instant now) {
        if (status == EventStatus.CANCELLED) {
            throw new EventStateConflictException(RefusalReason.EVENT_CANCELLED, "the event is already cancelled");
        }
        if (schedule().hasEnded(now)) {
            throw new EventStateConflictException(RefusalReason.EVENT_ENDED, ENDED);
        }
        status = EventStatus.CANCELLED;
    }

    /** Rascunho não existe para os usuários: nem na lista, nem pelo id. */
    public boolean isVisibleToParticipants() {
        return status != EventStatus.DRAFT;
    }

    /** Publicado e dentro do horário {@code [startsAt, endsAt)}: é quando as rodadas de pareamento acontecem. */
    public boolean isUnderway(Instant now) {
        return status == EventStatus.PUBLISHED && schedule().hasStarted(now) && !schedule().hasEnded(now);
    }

    /**
     * @param registrations quantas pessoas já estão inscritas, contadas com o evento travado
     * @throws EventStateConflictException se o evento não está publicado, já começou ou lotou
     */
    public void ensureAcceptsRegistration(long registrations, Instant now) {
        switch (status) {
            case DRAFT -> throw new EventStateConflictException(RefusalReason.EVENT_NOT_PUBLISHED,
                    "the event is not published");
            case CANCELLED -> throw new EventStateConflictException(RefusalReason.EVENT_CANCELLED,
                    "the event was cancelled");
            case PUBLISHED -> {
                ensureNotStarted(now);
                if (capacity().isFilledBy(registrations)) {
                    throw new EventStateConflictException(RefusalReason.EVENT_FULL, "the event is full");
                }
            }
        }
    }

    /** Depois do início, a vaga já foi usada (ou desperdiçada): sair não é mais possível. */
    public void ensureAllowsLeaving(Instant now) {
        ensureNotStarted(now);
    }

    /** Começado e encerrado são motivos diferentes para quem chama, embora os dois fechem a ação. */
    private void ensureNotStarted(Instant now) {
        if (schedule().hasEnded(now)) {
            throw new EventStateConflictException(RefusalReason.EVENT_ENDED, ENDED);
        }
        if (schedule().hasStarted(now)) {
            throw new EventStateConflictException(RefusalReason.EVENT_STARTED, "the event has already started");
        }
    }

    public UUID id() {
        return id;
    }

    public EventTitle title() {
        return new EventTitle(title);
    }

    public EventDescription description() {
        return new EventDescription(description);
    }

    public EventSchedule schedule() {
        return new EventSchedule(startsAt, endsAt);
    }

    public Capacity capacity() {
        return new Capacity(capacity);
    }

    public EventStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

}
