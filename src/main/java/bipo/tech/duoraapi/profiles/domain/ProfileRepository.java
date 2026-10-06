package bipo.tech.duoraapi.profiles.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ProfileRepository extends Repository<Profile, UUID> {

    Optional<Profile> findById(UUID accountId);

    /**
     * Garante a linha do perfil antes da primeira edição. Atômico e idempotente: com duas edições ao
     * mesmo tempo, a chave primária decide, sem "consultar e depois inserir".
     */
    @Modifying
    @Query(value = """
            insert into profile (account_id, version)
            values (:accountId, 0)
            on conflict (account_id) do nothing
            """, nativeQuery = true)
    void insertEmptyIfAbsent(UUID accountId);

    /** Grava já, para a versão nova (o ETag da resposta) e o conflito de versão saírem aqui, e não no commit. */
    void flush();

}
