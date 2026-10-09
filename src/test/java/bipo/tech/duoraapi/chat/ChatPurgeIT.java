package bipo.tech.duoraapi.chat;

import static bipo.tech.duoraapi.ConcurrentCalls.inAnotherThread;
import static bipo.tech.duoraapi.SchemaSupport.insertAccount;
import static bipo.tech.duoraapi.chat.ChatFixtures.ENDS_AT;
import static bipo.tech.duoraapi.chat.ChatFixtures.messagesPath;
import static bipo.tech.duoraapi.chat.ChatFixtures.newKey;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;

import bipo.tech.duoraapi.HeldLock;
import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.chat.application.ChatPurge;
import bipo.tech.duoraapi.events.EventFixtures;

/**
 * Expurgo do chat (docs/adr/0021, fatia 4): o chat e as mensagens saem de fato depois de purge_after, em lotes
 * limitados, e várias réplicas rodam juntas sem esperar uma pela outra nem apagar duas vezes. O relógio é o do
 * teste; o agendamento real não roda aqui (o intervalo de teste é de um dia, em config/application.properties).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class ChatPurgeIT {

    private static final Instant PURGE_AFTER = ENDS_AT.plus(Duration.ofHours(24));
    private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FAR_AHEAD = Instant.parse("2027-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TestClock clock;

    @Autowired
    private ChatPurge purge;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID first;
    private UUID second;

    @BeforeEach
    void resetState() {
        clock.setTo(TestClockConfiguration.NOW);
        EventFixtures.cleanDatabase(jdbcClient);
        first = insertAccount(jdbcClient, "oid-first");
        second = insertAccount(jdbcClient, "oid-second");
        if (first.toString().compareTo(second.toString()) > 0) {
            UUID swap = first;
            first = second;
            second = swap;
        }
    }

    /** Até purge_after, inclusive, a conversa fica; um microssegundo depois, sai o chat com as mensagens. */
    @Test
    void messagesArePurgedAfterTheRetention() throws Exception {
        String eventId = ChatFixtures.pairedInRoundOne(mockMvc, jdbcClient, clock, "ana", "bruno");
        ChatFixtures.send(mockMvc, eventId, user("ana"), newKey(), "oi").andExpect(status().isCreated());
        ChatFixtures.send(mockMvc, eventId, user("bruno"), newKey(), "olá").andExpect(status().isCreated());

        clock.setTo(PURGE_AFTER);
        assertThat(purge.purgeExpired(10, 10)).isZero();
        assertThat(chatRows()).isOne();
        assertThat(ChatFixtures.messageRows(jdbcClient)).isEqualTo(2);

        clock.setTo(PURGE_AFTER.plusNanos(1_000));
        assertThat(purge.purgeExpired(10, 10)).isOne();

        assertThat(chatRows()).isZero();
        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
        mockMvc.perform(get(messagesPath(eventId, 1)).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"items\": [], \"nextAfterSeq\": null}", JsonCompareMode.STRICT));
        mockMvc.perform(get(ChatFixtures.chatPath(eventId, 1)).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.lastSeq").value(0));
        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    @Test
    void chatsNotYetExpiredAreKept() {
        clock.setTo(TestClockConfiguration.NOW);
        insertChats(3, LONG_AGO);
        insertChats(2, FAR_AHEAD);

        assertThat(purge.purgeExpired(10, 10)).isEqualTo(3);

        assertThat(purgeAfterOfRemainingChats()).containsOnly(FAR_AHEAD);
    }

    /** Um lote por comando, e no máximo o número de lotes da execução: o resto fica para a próxima. */
    @Test
    void aRunStopsAtItsBatchLimit() {
        insertChats(25, LONG_AGO);

        assertThat(purge.purgeExpired(10, 2)).isEqualTo(20);
        assertThat(chatRows()).isEqualTo(5);

        assertThat(purge.purgeExpired(10, 2)).isEqualTo(5);
        assertThat(chatRows()).isZero();
    }

    /** Duas réplicas ao mesmo tempo: cada chat é apagado uma vez só, e nenhuma falha. */
    @Test
    void twoReplicasPurgeWithoutConflict() throws Exception {
        insertChats(200, LONG_AGO);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> runs = List.of(
                    executor.submit(() -> {
                        start.await();
                        return purge.purgeExpired(5, 100);
                    }),
                    executor.submit(() -> {
                        start.await();
                        return purge.purgeExpired(5, 100);
                    }));
            start.countDown();

            int deleted = 0;
            for (Future<Integer> run : runs) {
                deleted += run.get(30, TimeUnit.SECONDS);
            }
            assertThat(deleted).isEqualTo(200);
        }
        assertThat(chatRows()).isZero();
    }

    /**
     * Outra réplica no meio de um lote segura chats vencidos: esta pula os travados (skip locked), apaga o resto
     * sem esperar e deixa aqueles para a próxima execução.
     */
    @Test
    void aChatLockedByAnotherReplicaIsSkippedWithoutWaiting() throws Exception {
        insertChats(1, LONG_AGO.minus(Duration.ofDays(1)));
        insertChats(4, LONG_AGO);

        try (var _ = HeldLock.hold(transactionTemplate, () -> jdbcClient
                .sql("select id from chat order by purge_after limit 1 for update")
                .query(UUID.class).single())) {
            var deleted = inAnotherThread(() -> purge.purgeExpired(10, 10));

            assertThat(deleted).isEqualTo(4);
            assertThat(chatRows()).isOne();
        }

        assertThat(purge.purgeExpired(10, 10)).isOne();
        assertThat(chatRows()).isZero();
    }

    /** A métrica conta os chats vencidos que ainda não saíram, sem nada do conteúdo. */
    @Test
    void theBacklogMetricCountsExpiredChats() {
        insertChats(3, LONG_AGO);
        insertChats(1, FAR_AHEAD);
        var backlog = meterRegistry.get(ChatPurge.BACKLOG_METRIC).gauge();

        assertThat(backlog.value()).isEqualTo(3.0);

        purge.purgeExpired(10, 10);

        assertThat(backlog.value()).isZero();
    }

    private void insertChats(int count, Instant purgeAfter) {
        jdbcClient.sql("""
                        insert into chat (event_id, round_number, first_account_id, second_account_id, last_seq,
                                          purge_after, created_at)
                        select gen_random_uuid(), 1, :first, :second, 1, :purgeAfter, now()
                          from generate_series(1, :count)
                        """)
                .param("first", first).param("second", second)
                .param("purgeAfter", OffsetDateTime.ofInstant(purgeAfter, ZoneOffset.UTC))
                .param("count", count)
                .update();
        jdbcClient.sql("""
                        insert into chat_message (chat_id, seq, sender_account_id, body, idempotency_key, sent_at)
                        select id, 1, :first, 'm', gen_random_uuid(), now() from chat
                         where not exists (select 1 from chat_message m where m.chat_id = chat.id)
                        """)
                .param("first", first)
                .update();
    }

    private long chatRows() {
        return jdbcClient.sql("select count(*) from chat").query(Long.class).single();
    }

    private List<Instant> purgeAfterOfRemainingChats() {
        return jdbcClient.sql("select purge_after from chat")
                .query((row, number) -> row.getObject("purge_after", OffsetDateTime.class).toInstant())
                .list();
    }

}
