package bipo.tech.duoraapi.chat;

import static bipo.tech.duoraapi.chat.ChatFixtures.ENDS_AT;
import static bipo.tech.duoraapi.chat.ChatFixtures.chatPath;
import static bipo.tech.duoraapi.chat.ChatFixtures.newKey;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.chat.application.ChatPurge;
import bipo.tech.duoraapi.events.EventFixtures;

/**
 * A requisição que chega a um chat vencido enquanto o expurgo roda (docs/quality-review-2026-10.md, item 5.2.1).
 * A requisição começa pelo {@code insert ... on conflict} na tabela chat; o expurgo tem de cair logo depois dele,
 * antes do próximo comando. Para isso o teste instala uma barreira: um trigger por comando, depois do insert, que
 * espera um advisory lock segurado pelo teste. Com a requisição parada ali, o expurgo de verdade roda em outra
 * conexão, e só então a barreira abre. Sem a barreira a corrida só aparece por acaso.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class ChatPurgeRaceIT {

    /** Um microssegundo depois do purge_after do chat: o fim do evento mais 24 h. */
    private static final Instant EXPIRED = ENDS_AT.plus(Duration.ofHours(24)).plusNanos(1_000);

    /** A chave do advisory lock da barreira; nenhum código da aplicação usa a forma de dois inteiros com ela. */
    private static final int BARRIER_CLASS = 52;
    private static final int BARRIER_OBJECT = 1;

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestClock clock;

    @Autowired
    private ChatPurge purge;

    @BeforeEach
    void resetState() {
        clock.setTo(TestClockConfiguration.NOW);
        EventFixtures.cleanDatabase(jdbcClient);
    }

    @AfterEach
    void removeBarrier() {
        jdbcClient.sql("drop trigger if exists chat_purge_race_barrier on chat").update();
        jdbcClient.sql("drop function if exists chat_purge_race_barrier()").update();
    }

    /** O expurgo apaga o chat logo depois que a leitura passou pelo insert: a leitura responde sem erro. */
    @Test
    void aReadOfAChatPurgedInTheMiddleOfTheRequestAnswersWithoutError() throws Exception {
        String eventId = expiredChatWithOneMessage();
        installBarrier();

        try (var executor = Executors.newSingleThreadExecutor(); var barrier = closedBarrier()) {
            Future<MvcResult> read = executor.submit(() -> mockMvc.perform(get(chatPath(eventId, 1))
                    .with(user("ana"))).andReturn());
            awaitUntil(() -> read.isDone() || barrierHasAWaiter());

            purge.purgeExpired(10, 10);
            barrier.open();

            MvcResult result = read.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat((Boolean) JsonPath.read(result.getResponse().getContentAsString(), "$.open")).isFalse();
        }
    }

    /**
     * O expurgo roda enquanto um envio ao chat vencido está entre o insert e o resto: o envio recebe o 409 do chat
     * fechado, e o expurgo pula o chat que o envio segura, que sai na execução seguinte.
     */
    @Test
    void aSendToAChatPurgedInTheMiddleOfTheRequestIsRefusedAsClosed() throws Exception {
        String eventId = expiredChatWithOneMessage();
        installBarrier();
        int purgedDuringTheSend;

        try (var executor = Executors.newSingleThreadExecutor(); var barrier = closedBarrier()) {
            Future<MvcResult> send = executor.submit(() -> ChatFixtures.send(mockMvc, eventId, user("bruno"),
                    newKey(), "oi").andReturn());
            awaitUntil(() -> send.isDone() || barrierHasAWaiter());

            purgedDuringTheSend = purge.purgeExpired(10, 10);
            barrier.open();

            MvcResult result = send.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.reason"))
                    .isEqualTo("CHAT_CLOSED");
        }
        assertThat(purgedDuringTheSend).isZero();
        assertThat(purge.purgeExpired(10, 10)).isOne();
        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    /** Um chat com uma mensagem de um evento que acabou há mais de 24 h, e o relógio já depois do purge_after. */
    private String expiredChatWithOneMessage() throws Exception {
        String eventId = ChatFixtures.pairedInRoundOne(mockMvc, jdbcClient, clock, "ana", "bruno");
        ChatFixtures.send(mockMvc, eventId, user("ana"), newKey(), "oi").andExpect(status().isCreated());
        clock.setTo(EXPIRED);
        return eventId;
    }

    /** Depois de todo insert na tabela chat, o comando espera o advisory lock da barreira, se alguém o segura. */
    private void installBarrier() {
        jdbcClient.sql("""
                        create function chat_purge_race_barrier() returns trigger language plpgsql as $$
                        begin
                            perform pg_advisory_xact_lock(%d, %d);
                            return null;
                        end
                        $$
                        """.formatted(BARRIER_CLASS, BARRIER_OBJECT))
                .update();
        jdbcClient.sql("""
                        create trigger chat_purge_race_barrier after insert on chat
                           for each statement execute function chat_purge_race_barrier()
                        """)
                .update();
    }

    private Barrier closedBarrier() throws SQLException {
        Connection connection = dataSource.getConnection();
        try (var statement = connection.prepareStatement("select pg_advisory_lock(?, ?)")) {
            statement.setInt(1, BARRIER_CLASS);
            statement.setInt(2, BARRIER_OBJECT);
            statement.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return new Barrier(connection);
    }

    private boolean barrierHasAWaiter() {
        return jdbcClient.sql("""
                        select exists (select 1 from pg_locks
                                        where locktype = 'advisory' and not granted
                                          and classid = :classId and objid = :objectId)
                        """)
                .param("classId", BARRIER_CLASS)
                .param("objectId", BARRIER_OBJECT)
                .query(Boolean.class)
                .single();
    }

    /** Espera por consulta ao banco, não por tempo: o sleep só espaça as consultas até o prazo. */
    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("waited too long for the request to reach the barrier").isBefore(deadline);
            Thread.sleep(5);
        }
    }

    /**
     * A conexão volta ao pool sem encerrar a sessão, e o advisory lock de sessão iria junto: o close solta todos,
     * mesmo se o teste falhar antes de abrir a barreira.
     */
    private record Barrier(Connection connection) implements AutoCloseable {

        void open() throws SQLException {
            try (var statement = connection.prepareStatement("select pg_advisory_unlock_all()")) {
                statement.execute();
            }
        }

        @Override
        public void close() throws SQLException {
            try (connection) {
                open();
            }
        }

    }

}
