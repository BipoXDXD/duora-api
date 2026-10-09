package bipo.tech.duoraapi.chat;

import static bipo.tech.duoraapi.ConcurrentCalls.sameCallTogether;
import static bipo.tech.duoraapi.ConcurrentCalls.statusCodeOf;
import static bipo.tech.duoraapi.ConcurrentCalls.together;
import static bipo.tech.duoraapi.chat.ChatFixtures.ENDS_AT;
import static bipo.tech.duoraapi.chat.ChatFixtures.IDEMPOTENCY_KEY;
import static bipo.tech.duoraapi.chat.ChatFixtures.chatPath;
import static bipo.tech.duoraapi.chat.ChatFixtures.messagePath;
import static bipo.tech.duoraapi.chat.ChatFixtures.messagesPath;
import static bipo.tech.duoraapi.chat.ChatFixtures.newKey;
import static bipo.tech.duoraapi.chat.ChatFixtures.textBody;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.registerWithCompleteProfile;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import bipo.tech.duoraapi.HeldLock;
import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.events.EventFixtures;

/**
 * Chat temporário do par da rodada (docs/adr/0021): só os dois do par leem e escrevem, a sequência segue a
 * ordem de commit sem lacunas, o reenvio com a mesma Idempotency-Key grava uma vez, e o chat fecha na rodada
 * seguinte, no fim do evento, no limite de mensagens e no bloqueio, sempre com a mesma resposta. O limite
 * por conta fica alto aqui; ele tem o próprio teste (ChatRateLimitIT).
 */
@SpringBootTest(properties = "duora.chat.message-rate-limit.capacity=100000")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class ChatIT {

    private static final String SENT_AT = EventFixtures.STARTS_AT;
    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";
    private static final String CLOSED_DETAIL = "the chat is closed";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TestClock clock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetState() {
        clock.setTo(TestClockConfiguration.NOW);
        EventFixtures.cleanDatabase(jdbcClient);
    }

    @Test
    void theChatOpensAtTheDrawWithoutMessages() throws Exception {
        String eventId = paired("ana", "bruno");

        mockMvc.perform(get(chatPath(eventId, 1)).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.lastSeq").value(0));
        mockMvc.perform(get(messagesPath(eventId, 1)).with(user("bruno")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"items\": [], \"nextAfterSeq\": null}", JsonCompareMode.STRICT));
    }

    @Test
    void aMessageIsSentAndBothOfThePairReadIt() throws Exception {
        String eventId = paired("ana", "bruno");

        send(eventId, "ana", newKey(), "oi, tudo bem?")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, messagePath(eventId, 1, 1)))
                .andExpect(content().json("""
                        {"seq": 1, "fromMe": true, "text": "oi, tudo bem?", "sentAt": "%s"}
                        """.formatted(SENT_AT), JsonCompareMode.STRICT));

        mockMvc.perform(get(messagesPath(eventId, 1)).with(user("bruno")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [{"seq": 1, "fromMe": false, "text": "oi, tudo bem?", "sentAt": "%s"}],
                         "nextAfterSeq": null}
                        """.formatted(SENT_AT), JsonCompareMode.STRICT));
        mockMvc.perform(get(messagePath(eventId, 1, 1)).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"seq": 1, "fromMe": true, "text": "oi, tudo bem?", "sentAt": "%s"}
                        """.formatted(SENT_AT), JsonCompareMode.STRICT));
        String anaSees = chatBodyOf(eventId, "ana");
        assertThat(anaSees).isEqualTo(chatBodyOf(eventId, "bruno"));
        assertThat(JsonPath.<Integer>read(anaSees, "$.lastSeq")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(anaSees, "$.open")).isTrue();
        assertThat(JsonPath.<String>read(anaSees, "$.chatId")).isEqualTo(chatIdOf(eventId));
    }

    @Test
    void theTextIsStoredNormalized() throws Exception {
        String eventId = paired("ana", "bruno");

        send(eventId, "ana", newKey(), "  café\r\nde tarde?  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("café\nde tarde?"));
    }

    @Test
    void messagesArePagedByTheirPosition() throws Exception {
        String eventId = paired("ana", "bruno");
        for (int i = 1; i <= 3; i++) {
            send(eventId, i % 2 == 0 ? "bruno" : "ana", newKey(), "mensagem " + i).andExpect(status().isCreated());
        }

        String first = mockMvc.perform(get(messagesPath(eventId, 1)).param("maxPageSize", "2").with(user("ana")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(first, "$.items[*].seq")).containsExactly(1, 2);
        assertThat(JsonPath.<List<Boolean>>read(first, "$.items[*].fromMe")).containsExactly(true, false);
        assertThat(JsonPath.<Integer>read(first, "$.nextAfterSeq")).isEqualTo(2);

        mockMvc.perform(get(messagesPath(eventId, 1)).param("afterSeq", "2").param("maxPageSize", "2")
                        .with(user("ana")))
                .andExpect(jsonPath("$.items[*].seq").value(3))
                .andExpect(jsonPath("$.nextAfterSeq").isEmpty());
        mockMvc.perform(get(messagesPath(eventId, 1)).param("afterSeq", "3").with(user("ana")))
                .andExpect(content().json("{\"items\": [], \"nextAfterSeq\": null}", JsonCompareMode.STRICT));
    }

    /** Um terceiro não lê nem escreve, e a resposta é a mesma de rodada e evento inexistentes (BOLA). */
    @Test
    void someoneOutsideThePairCannotReadNorSend() throws Exception {
        String otherEvent = createPublishedEvent(mockMvc);
        registerWithCompleteProfile(mockMvc, user("davi"), otherEvent);
        String eventId = paired("ana", "bruno", "carla");
        String satOut = sittingOutIn(eventId);
        String paired = satOut.equals("ana") ? "bruno" : "ana";
        send(eventId, paired, newKey(), "segredo da dupla").andExpect(status().isCreated());

        var bodies = new ArrayList<String>();
        for (String outsider : List.of("davi", satOut)) {
            for (var request : List.of(
                    get(chatPath(eventId, 1)), get(messagesPath(eventId, 1)), get(messagePath(eventId, 1, 1)),
                    post(messagesPath(eventId, 1)).header(IDEMPOTENCY_KEY, newKey())
                            .contentType(MediaType.APPLICATION_JSON).content(textBody("oi")))) {
                bodies.add(mockMvc.perform(request.with(user(outsider)))
                        .andExpect(status().isNotFound())
                        .andReturn().getResponse().getContentAsString());
            }
        }
        bodies.add(mockMvc.perform(get(chatPath(eventId, 2)).with(user(paired)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        bodies.add(mockMvc.perform(get(chatPath(UUID.randomUUID().toString(), 1)).with(user(paired)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());

        assertThat(bodies)
                .extracting(body -> JsonPath.<String>read(body, "$.detail"))
                .containsOnly("the caller has no chat in this round");
        assertThat(bodies).allSatisfy(body -> assertThat(body).doesNotContain("segredo", chatIdOf(eventId),
                accountOf("ana"), accountOf("bruno"), accountOf("carla")));
        assertThat(ChatFixtures.messageRows(jdbcClient)).isEqualTo(1);
        assertThat(jdbcClient.sql("select count(*) from chat").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aPositionWithoutMessageIsNotFound() throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "ana", newKey(), "oi").andExpect(status().isCreated());

        mockMvc.perform(get(messagePath(eventId, 1, 2)).with(user("bruno")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("there is no message at this position of the chat"));
    }

    @Test
    void aRetriedSendWithTheSameKeyRecordsOneMessage() throws Exception {
        String eventId = paired("ana", "bruno");
        String key = newKey();
        String created = send(eventId, "ana", key, "oi").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        clock.advance(Duration.ofSeconds(30));

        String repeated = send(eventId, "ana", key, "oi")
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andReturn().getResponse().getContentAsString();

        assertThat(repeated).isEqualTo(created);
        assertThat(ChatFixtures.messageRows(jdbcClient)).isEqualTo(1);
        assertThat(lastSeqOf(eventId)).isEqualTo(1);
    }

    /** A rede caiu antes da resposta e o chat fechou em seguida: o reenvio ainda recebe a mensagem gravada. */
    @Test
    void aRetryAfterTheChatClosedStillAnswersTheRecordedMessage() throws Exception {
        String eventId = paired("ana", "bruno");
        String key = newKey();
        send(eventId, "ana", key, "oi").andExpect(status().isCreated());
        clock.setTo(ENDS_AT);

        send(eventId, "ana", key, "oi")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(1));
    }

    @Test
    void sameKeyWithAnotherTextIsAConflict() throws Exception {
        String eventId = paired("ana", "bruno");
        String key = newKey();
        send(eventId, "ana", key, "oi").andExpect(status().isCreated());

        send(eventId, "ana", key, "tchau")
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.reason").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.detail").value("the idempotency key was already used with another text"));

        assertThat(ChatFixtures.messageRows(jdbcClient)).isEqualTo(1);
        mockMvc.perform(get(messagePath(eventId, 1, 1)).with(user("bruno"))).andExpect(jsonPath("$.text").value("oi"));
    }

    /** A chave é de quem envia: a mesma, vinda do par, é outro envio e nunca devolve a mensagem alheia. */
    @Test
    void anotherAccountWithTheSameKeyGetsItsOwnMessage() throws Exception {
        String eventId = paired("ana", "bruno");
        String key = newKey();
        send(eventId, "ana", key, "oi").andExpect(status().isCreated());

        send(eventId, "bruno", key, "oi")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(2))
                .andExpect(jsonPath("$.fromMe").value(true));

        assertThat(ChatFixtures.messageRows(jdbcClient)).isEqualTo(2);
    }

    /** A mesma pessoa reenviando em paralelo (duas abas, retry agressivo): uma mensagem só. */
    @RepeatedTest(3)
    void concurrentSendsWithTheSameKeyRecordOne() throws Exception {
        String eventId = paired("ana", "bruno");
        String key = newKey();

        var statuses = sameCallTogether(4, statusCodeOf(() -> send(eventId, "ana", key, "oi")));

        assertThat(statuses).containsOnly(201, 200).containsOnlyOnce(201);
        assertThat(ChatFixtures.messageRows(jdbcClient)).isEqualTo(1);
        assertThat(lastSeqOf(eventId)).isEqualTo(1);
    }

    /** Dois envios ao mesmo tempo, um de cada lado: posições 1 e 2, nunca a mesma e nunca uma lacuna. */
    @RepeatedTest(5)
    void concurrentSendsGetConsecutiveSequenceNumbers() throws Exception {
        String eventId = paired("ana", "bruno");

        var statuses = together(List.of(
                statusCodeOf(() -> send(eventId, "ana", newKey(), "oi")),
                statusCodeOf(() -> send(eventId, "bruno", newKey(), "olá"))));

        assertThat(statuses).containsExactly(201, 201);
        assertThat(seqsOf(eventId)).containsExactly(1, 2);
        assertThat(lastSeqOf(eventId)).isEqualTo(2);
    }

    /** Com mais envios ao mesmo tempo a disputa pelo chat é certa: todos gravam, de 1 a 8, sem repetir. */
    @RepeatedTest(3)
    void manyConcurrentSendsGetConsecutiveSequenceNumbers() throws Exception {
        String eventId = paired("ana", "bruno");
        int sends = 8;

        var statuses = together(IntStream.range(0, sends)
                .mapToObj(i -> statusCodeOf(() -> send(eventId, i % 2 == 0 ? "ana" : "bruno", newKey(), "oi")))
                .toList());

        assertThat(statuses).containsOnly(201);
        assertThat(seqsOf(eventId)).containsExactlyElementsOf(IntStream.rangeClosed(1, sends).boxed().toList());
        assertThat(lastSeqOf(eventId)).isEqualTo(sends);
    }

    /**
     * O polling de quem lê a cada commit nunca pula uma mensagem: com a sequência em ordem de commit, toda
     * página continua exatamente da maior posição já vista, sem buraco que só se preencheria depois.
     */
    @RepeatedTest(3)
    void aReaderAfterEachCommitNeverSkipsAMessage() throws Exception {
        String eventId = paired("ana", "bruno");
        int writers = 3;
        int messagesEach = 6;
        int total = writers * messagesEach;
        var start = new CountDownLatch(1);
        var writing = new AtomicBoolean(true);
        var seen = new ArrayList<Integer>();

        try (var executor = Executors.newFixedThreadPool(writers + 1)) {
            var sends = new ArrayList<Future<?>>();
            for (int writer = 0; writer < writers; writer++) {
                String name = writer % 2 == 0 ? "ana" : "bruno";
                sends.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < messagesEach; i++) {
                        send(eventId, name, newKey(), "m").andExpect(status().isCreated());
                    }
                    return null;
                }));
            }
            Future<?> reader = executor.submit(() -> {
                start.await();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (seen.size() < total && System.nanoTime() < deadline) {
                    // Lido antes do GET: só uma página vazia pedida depois do último commit prova um pulo.
                    boolean writersDoneBeforeRead = !writing.get();
                    int afterSeq = seen.isEmpty() ? 0 : seen.getLast();
                    String page = mockMvc.perform(get(messagesPath(eventId, 1))
                                    .param("afterSeq", Integer.toString(afterSeq)).with(user("bruno")))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    List<Integer> seqs = JsonPath.read(page, "$.items[*].seq");
                    assertThat(seqs).as("página depois de %d", afterSeq)
                            .containsExactlyElementsOf(IntStream.rangeClosed(afterSeq + 1, afterSeq + seqs.size())
                                    .boxed().toList());
                    seen.addAll(seqs);
                    if (writersDoneBeforeRead && seqs.isEmpty() && seen.size() < total) {
                        throw new AssertionError("o leitor parou em " + seen.size() + " de " + total);
                    }
                }
                return null;
            });
            start.countDown();
            for (var sent : sends) {
                sent.get(60, TimeUnit.SECONDS);
            }
            writing.set(false);
            reader.get(60, TimeUnit.SECONDS);
        }

        assertThat(seen).containsExactlyElementsOf(IntStream.rangeClosed(1, total).boxed().toList());
    }

    /** O caso prioritário do plano (§8): bloquear durante a conversa impede novos envios, dos dois lados. */
    @ParameterizedTest
    @ValueSource(strings = {"ana", "bruno"})
    void aBlockEitherWayStopsNewMessages(String blocker) throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "ana", newKey(), "antes do bloqueio").andExpect(status().isCreated());
        String blocked = blocker.equals("ana") ? "bruno" : "ana";
        block(blocker, blocked);

        for (String sender : List.of("ana", "bruno")) {
            send(eventId, sender, newKey(), "depois do bloqueio")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("CHAT_CLOSED"))
                    .andExpect(jsonPath("$.detail").value(CLOSED_DETAIL));
            mockMvc.perform(get(chatPath(eventId, 1)).with(user(sender)))
                    .andExpect(jsonPath("$.open").value(false));
            mockMvc.perform(get(messagesPath(eventId, 1)).with(user(sender)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].text").value("antes do bloqueio"));
        }
        assertThat(ChatFixtures.messageRows(jdbcClient)).isEqualTo(1);
    }

    /** Quem foi bloqueado vê o mesmo que veria no fim da rodada: status, corpo e chat (docs/adr/0015). */
    @Test
    void aBlockedChatLooksLikeARoundThatEnded() throws Exception {
        String blockedChat = paired("ana", "bruno");
        block("ana", "bruno");
        String refusedByBlock = send(blockedChat, "bruno", newKey(), "oi").andReturn().getResponse()
                .getContentAsString();
        String chatAfterBlock = chatBodyOf(blockedChat, "bruno");

        String endedChat = paired("carla", "davi");
        ChatFixtures.startRound(mockMvc, endedChat, 2);
        var refusedByRound = send(endedChat, "davi", newKey(), "oi").andReturn().getResponse();
        String chatAfterRound = chatBodyOf(endedChat, "davi");

        assertThat(refusedByRound.getStatus()).isEqualTo(409);
        assertThat(withoutEventId(refusedByBlock, blockedChat))
                .isEqualTo(withoutEventId(refusedByRound.getContentAsString(), endedChat));
        assertThat(withoutChatId(chatAfterBlock)).isEqualTo(withoutChatId(chatAfterRound))
                .isEqualTo("{\"chatId\":\"<chat>\",\"open\":false,\"lastSeq\":0}");
    }

    @Test
    void aChatClosesWhenTheNextRoundStarts() throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "ana", newKey(), "rodada 1").andExpect(status().isCreated());

        ChatFixtures.startRound(mockMvc, eventId, 2);

        send(eventId, "bruno", newKey(), "ainda dá?")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CHAT_CLOSED"));
        mockMvc.perform(get(chatPath(eventId, 1)).with(user("ana"))).andExpect(jsonPath("$.open").value(false));
        mockMvc.perform(get(messagesPath(eventId, 1)).with(user("bruno")))
                .andExpect(jsonPath("$.items[0].text").value("rodada 1"));
        assertThat(ChatFixtures.messageRows(jdbcClient)).isEqualTo(1);
    }

    /** O evento é o intervalo semiaberto [início, fim): no último microssegundo ainda aceita; no fim, não. */
    @Test
    void aChatClosesWhenTheEventEnds() throws Exception {
        String eventId = paired("ana", "bruno");
        clock.setTo(ENDS_AT.minusNanos(1_000));
        send(eventId, "ana", newKey(), "último").andExpect(status().isCreated());

        clock.setTo(ENDS_AT);

        send(eventId, "bruno", newKey(), "tarde demais")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CHAT_CLOSED"));
        mockMvc.perform(get(chatPath(eventId, 1)).with(user("ana"))).andExpect(jsonPath("$.open").value(false));
        assertThat(ChatFixtures.messageRows(jdbcClient)).isEqualTo(1);
    }

    @Test
    void aFullChatRefusesNewMessages() throws Exception {
        String eventId = paired("ana", "bruno");
        mockMvc.perform(get(chatPath(eventId, 1)).with(user("ana"))).andExpect(status().isOk());
        fillChatUpTo(eventId, 299);

        send(eventId, "ana", newKey(), "a trecentésima").andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(300));
        send(eventId, "bruno", newKey(), "uma a mais")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CHAT_CLOSED"))
                .andExpect(jsonPath("$.detail").value(CLOSED_DETAIL));

        mockMvc.perform(get(chatPath(eventId, 1)).with(user("bruno")))
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.lastSeq").value(300));
        assertThat(ChatFixtures.messageRows(jdbcClient)).isEqualTo(300);
    }

    @Test
    void fiveHundredCharactersAreAccepted() throws Exception {
        String eventId = paired("ana", "bruno");

        send(eventId, "ana", newKey(), "😀".repeat(500)).andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"501 caracteres", "nul", "zero-width", "direção", "em branco"})
    void invalidTextIsRejectedWithoutWriting(String kind) throws Exception {
        String eventId = paired("ana", "bruno");
        String text = switch (kind) {
            case "501 caracteres" -> "a".repeat(501);
            case "nul" -> "oi\u0000";
            case "zero-width" -> "oi​tchau";
            case "direção" -> "oi‮tchau";
            default -> "   ";
        };

        send(eventId, "ana", newKey(), text)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("text"));

        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid", "01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b0"})
    void anInvalidIdempotencyKeyIsABadRequestAndWritesNothing(String key) throws Exception {
        String eventId = paired("ana", "bruno");

        send(eventId, "ana", key, "oi")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    @Test
    void aMissingIdempotencyKeyIsABadRequest() throws Exception {
        String eventId = paired("ana", "bruno");

        mockMvc.perform(post(messagesPath(eventId, 1)).with(user("ana"))
                        .contentType(MediaType.APPLICATION_JSON).content(textBody("oi")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "abc", "1.5"})
    void anInvalidRoundNumberIsABadRequest(String number) throws Exception {
        String eventId = paired("ana", "bruno");
        String chat = "/api/events/" + eventId + "/rounds/" + number + "/chat";

        mockMvc.perform(get(chat).with(user("ana"))).andExpect(status().isBadRequest());
        mockMvc.perform(get(chat + "/messages").with(user("ana"))).andExpect(status().isBadRequest());
        mockMvc.perform(get(chat + "/messages/1").with(user("ana"))).andExpect(status().isBadRequest());
        mockMvc.perform(post(chat + "/messages").with(user("ana")).header(IDEMPOTENCY_KEY, newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(textBody("oi")))
                .andExpect(status().isBadRequest());

        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "301", "-1", "abc"})
    void anInvalidPositionIsABadRequest(String seq) throws Exception {
        String eventId = paired("ana", "bruno");

        mockMvc.perform(get(messagesPath(eventId, 1) + "/" + seq).with(user("ana")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"afterSeq=-1", "afterSeq=301", "afterSeq=", "afterSeq=x", "maxPageSize=0",
            "maxPageSize=101", "maxPageSize=", "maxPageSize=2.5"})
    void anInvalidCursorOrPageSizeIsABadRequest(String query) throws Exception {
        String eventId = paired("ana", "bruno");

        mockMvc.perform(get(messagesPath(eventId, 1) + "?" + query).with(user("ana")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    /** O mesmo ProblemDetail das outras listas paginadas, com o teto desta lista e sem o valor recebido. */
    @Test
    void pageSizeOutsideTheLimitsNamesTheLimitOfThisList() throws Exception {
        String eventId = paired("ana", "bruno");

        mockMvc.perform(get(messagesPath(eventId, 1)).param("maxPageSize", "101").with(user("ana")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400,
                         "detail": "maxPageSize must be between 1 and 100", "instance": "%s"}
                        """.formatted(messagesPath(eventId, 1)), JsonCompareMode.STRICT));
    }

    @Test
    void aWebSessionWithoutCsrfTokenCannotSend() throws Exception {
        String eventId = paired("ana", "bruno");

        mockMvc.perform(post(messagesPath(eventId, 1)).with(webSession("ana")).header(IDEMPOTENCY_KEY, newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(textBody("oi")))
                .andExpect(status().isForbidden());

        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    @Test
    void aWebSessionWithCsrfTokenSends() throws Exception {
        String eventId = paired("ana", "bruno");

        mockMvc.perform(post(messagesPath(eventId, 1)).with(webSession("ana")).with(csrf())
                        .header(IDEMPOTENCY_KEY, newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(textBody("oi")))
                .andExpect(status().isCreated());
    }

    /** Outro envio segura o chat além do teto: 503, nada gravado, e o reenvio com a mesma chave é seguro. */
    @Test
    void aSendIsRefusedWhenAnotherHoldsTheChatTooLong() throws Exception {
        String eventId = paired("ana", "bruno");
        mockMvc.perform(get(chatPath(eventId, 1)).with(user("ana"))).andExpect(status().isOk());

        try (var _ = HeldLock.hold(transactionTemplate, () -> jdbcClient
                .sql("select id from chat where event_id = cast(:id as uuid) for update")
                .param("id", eventId).query(UUID.class).single())) {
            send(eventId, "ana", newKey(), "oi")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        }

        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    private String paired(String... names) throws Exception {
        return ChatFixtures.pairedInRoundOne(mockMvc, jdbcClient, clock, names);
    }

    private ResultActions send(String eventId, String name, String key, String text) throws Exception {
        return ChatFixtures.send(mockMvc, eventId, user(name), key, text);
    }

    private void block(String blocker, String blocked) throws Exception {
        mockMvc.perform(post("/api/accounts/{id}:block", accountOf(blocked)).with(user(blocker)))
                .andExpect(status().isNoContent());
    }

    private String chatBodyOf(String eventId, String name) throws Exception {
        return mockMvc.perform(get(chatPath(eventId, 1)).with(user(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Mensagens de mentira até a posição pedida, direto no banco: o caminho da API é o que vem depois. */
    private void fillChatUpTo(String eventId, int lastSeq) {
        String chatId = chatIdOf(eventId);
        jdbcClient.sql("""
                        insert into chat_message (chat_id, seq, sender_account_id, body, idempotency_key, sent_at)
                        select cast(:chatId as uuid), seq, cast(:sender as uuid), 'm', gen_random_uuid(), now()
                          from generate_series(1, :lastSeq) as seq
                        """)
                .param("chatId", chatId).param("sender", accountOf("ana")).param("lastSeq", lastSeq).update();
        jdbcClient.sql("update chat set last_seq = :lastSeq where id = cast(:chatId as uuid)")
                .param("lastSeq", lastSeq).param("chatId", chatId).update();
    }

    private String chatIdOf(String eventId) {
        return jdbcClient.sql("select id::text from chat where event_id = cast(:id as uuid)")
                .param("id", eventId).query(String.class).single();
    }

    private int lastSeqOf(String eventId) {
        return jdbcClient.sql("select last_seq from chat where event_id = cast(:id as uuid)")
                .param("id", eventId).query(Integer.class).single();
    }

    private List<Integer> seqsOf(String eventId) {
        return jdbcClient.sql("""
                        select m.seq from chat_message m join chat c on c.id = m.chat_id
                         where c.event_id = cast(:id as uuid) order by m.seq
                        """)
                .param("id", eventId).query(Integer.class).list();
    }

    private String sittingOutIn(String eventId) {
        String account = jdbcClient.sql("""
                        select account_id from round_seat
                         where event_id = cast(:id as uuid) and round_number = 1 and partner_account_id is null
                        """)
                .param("id", eventId).query(String.class).single();
        return jdbcClient.sql("select subject from account where id = cast(:id as uuid)")
                .param("id", account).query(String.class).single().substring("oid-".length());
    }

    private String accountOf(String name) {
        return ChatFixtures.accountOf(jdbcClient, name);
    }

    private static String withoutEventId(String text, String eventId) {
        return text.replace(eventId, "<event>");
    }

    private static String withoutChatId(String body) {
        return body.replaceAll("\"chatId\":\"[^\"]+\"", "\"chatId\":\"<chat>\"");
    }

    private static RequestPostProcessor webSession(String name) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).claim("oid", "oid-" + name));
    }

}
