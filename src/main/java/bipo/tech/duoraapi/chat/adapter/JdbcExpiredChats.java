package bipo.tech.duoraapi.chat.adapter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import bipo.tech.duoraapi.chat.domain.ExpiredChats;

/**
 * Expurgo na tabela chat, por SQL (docs/adr/0021). As mensagens saem pela FK {@code on delete cascade}, no mesmo
 * comando. Cada lote é um comando só, então a transação dura o tempo dele e não há lock entre réplicas além das
 * linhas do próprio lote.
 */
@Repository
class JdbcExpiredChats implements ExpiredChats {

    private final JdbcClient jdbcClient;

    JdbcExpiredChats(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * {@code skip locked}: um chat que outra réplica está apagando, ou que um envio segura, não faz este lote
     * esperar nem é apagado duas vezes; fica para a próxima execução, se ainda existir.
     */
    @Override
    public int deleteBatch(Instant now, int limit) {
        return jdbcClient.sql("""
                        delete from chat
                         where id in (select id from chat
                                       where purge_after < :now
                                       order by purge_after
                                       limit :limit
                                         for update skip locked)
                        """)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("limit", limit)
                .update();
    }

    @Override
    public long count(Instant now) {
        return jdbcClient.sql("select count(*) from chat where purge_after < :now")
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

}
