package bipo.tech.duoraapi.events.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

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

    /** Grava já, para o conflito de versão (publicar e cancelar ao mesmo tempo) sair aqui, e não no commit. */
    void flush();

}
