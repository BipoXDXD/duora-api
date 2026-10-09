package bipo.tech.duoraapi.events.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface EventRepository extends Repository<Event, UUID> {

    Event save(Event event);

    Optional<Event> findById(UUID id);

    /**
     * O evento com a linha travada ({@code select ... for update}) até o fim da transação. As inscrições
     * de um mesmo evento passam por aqui e entram uma por vez, então a contagem feita em seguida vale
     * até o commit (docs/adr/0016). A espera pelo lock tem teto: ver
     * {@link RegistrationRepository#limitLockWait()}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from Event event where event.id = :id")
    Optional<Event> findByIdForUpdate(UUID id);

    /**
     * Eventos publicados que ainda não começaram, por início e, no empate, por id, a partir do par
     * (afterStartsAt, afterId) exclusive: paginação por keyset. A primeira página parte de (now, menor id).
     */
    @Query("""
            select event from Event event
             where event.status = :status
               and event.startsAt > :now
               and (event.startsAt > :afterStartsAt or (event.startsAt = :afterStartsAt and event.id > :afterId))
             order by event.startsAt, event.id
            """)
    List<Event> findUpcoming(EventStatus status, Instant now, Instant afterStartsAt, UUID afterId, Limit limit);

    /**
     * Eventos de qualquer um dos estados pedidos, do início mais distante ao mais antigo e, no empate, do maior
     * id ao menor, a partir do par (beforeStartsAt, beforeId) exclusive: paginação por keyset da lista do ADMIN.
     * A primeira página parte de um par depois de qualquer evento possível.
     */
    @Query("""
            select event from Event event
             where event.status in :statuses
               and (event.startsAt < :beforeStartsAt or (event.startsAt = :beforeStartsAt and event.id < :beforeId))
             order by event.startsAt desc, event.id desc
            """)
    List<Event> findForAdministration(Collection<EventStatus> statuses, Instant beforeStartsAt, UUID beforeId,
            Limit limit);

    /** Grava já, para o conflito de versão (publicar e cancelar ao mesmo tempo) sair aqui, e não no commit. */
    void flush();

}
