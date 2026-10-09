package bipo.tech.duoraapi.chat;

import static bipo.tech.duoraapi.SchemaSupport.assertViolates;
import static bipo.tech.duoraapi.SchemaSupport.insertAccount;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * As invariantes do chat também valem no banco, como última defesa contra um caminho que passe por fora do
 * domínio (docs/adr/0021).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatSchemaIT {

    private static final UUID EVENT = UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b");

    @Autowired
    private JdbcClient jdbcClient;

    private UUID first;
    private UUID second;

    @BeforeEach
    void setUp() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
        UUID one = insertAccount(jdbcClient, "oid-ana");
        UUID other = insertAccount(jdbcClient, "oid-bruno");
        boolean oneFirst = one.toString().compareTo(other.toString()) < 0;
        first = oneFirst ? one : other;
        second = oneFirst ? other : one;
    }

    @Test
    void aSequenceNumberIsUniqueInTheChat() {
        UUID chat = insertChat(1, first, second);
        insertMessage(chat, 1, first, UUID.randomUUID());

        assertViolates("chat_message_pkey", () -> insertMessage(chat, 1, second, UUID.randomUUID()));
    }

    @Test
    void anIdempotencyKeyRecordsOneMessagePerSender() {
        UUID chat = insertChat(1, first, second);
        UUID key = UUID.randomUUID();
        insertMessage(chat, 1, first, key);
        insertMessage(chat, 2, second, key);

        assertViolates("chat_message_idempotency_key_once", () -> insertMessage(chat, 3, first, key));
    }

    @Test
    void aPairHasOneChatPerRound() {
        insertChat(1, first, second);
        insertChat(2, first, second);

        assertViolates("chat_one_per_pair_and_round", () -> insertChat(1, first, second));
    }

    @Test
    void thePairIsStoredInTheOrderOfTheUuidType() {
        assertViolates("chat_normalized_pair", () -> insertChat(1, second, first));
        assertViolates("chat_normalized_pair", () -> insertChat(1, first, first));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 301})
    void aSequenceNumberGoesFromOneToThreeHundred(int seq) {
        UUID chat = insertChat(1, first, second);

        assertViolates("chat_message_seq_check", () -> insertMessage(chat, seq, first, UUID.randomUUID()));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 301})
    void theLastSequenceGoesFromZeroToThreeHundred(int lastSeq) {
        UUID chat = insertChat(1, first, second);

        assertViolates("chat_last_seq_check", () -> jdbcClient.sql("update chat set last_seq = :lastSeq where id = :id")
                        .param("lastSeq", lastSeq).param("id", chat).update());
    }

    @Test
    void theTextHasOneToFiveHundredCharacters() {
        UUID chat = insertChat(1, first, second);
        insertMessage(chat, 1, first, UUID.randomUUID(), "😀".repeat(500));

        assertViolates("chat_message_body_check", () -> insertMessage(chat, 2, first, UUID.randomUUID(), ""));
        assertViolates("chat_message_body_check",
                () -> insertMessage(chat, 2, first, UUID.randomUUID(), "a".repeat(501)));
    }

    /** O expurgo apaga o chat; as mensagens vão junto, de verdade (plano, seção 4). */
    @Test
    void deletingTheChatDeletesItsMessages() {
        UUID chat = insertChat(1, first, second);
        insertMessage(chat, 1, first, UUID.randomUUID());

        jdbcClient.sql("delete from chat where id = :id").param("id", chat).update();

        assertThat(jdbcClient.sql("select count(*) from chat_message").query(Long.class).single()).isZero();
    }

    private UUID insertChat(int roundNumber, UUID firstAccount, UUID secondAccount) {
        return jdbcClient.sql("""
                        insert into chat (event_id, round_number, first_account_id, second_account_id, purge_after,
                                          created_at)
                        values (:eventId, :roundNumber, :first, :second, now() + interval '1 day', now())
                        returning id
                        """)
                .param("eventId", EVENT).param("roundNumber", roundNumber)
                .param("first", firstAccount).param("second", secondAccount)
                .query(UUID.class).single();
    }

    private void insertMessage(UUID chat, int seq, UUID sender, UUID key) {
        insertMessage(chat, seq, sender, key, "oi");
    }

    private void insertMessage(UUID chat, int seq, UUID sender, UUID key, String body) {
        jdbcClient.sql("""
                        insert into chat_message (chat_id, seq, sender_account_id, body, idempotency_key, sent_at)
                        values (:chat, :seq, :sender, :body, :key, now())
                        """)
                .param("chat", chat).param("seq", seq).param("sender", sender).param("body", body)
                .param("key", key)
                .update();
    }

}
