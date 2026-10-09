package bipo.tech.duoraapi.chat.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.chat.domain.Chat;
import bipo.tech.duoraapi.chat.domain.ChatKey;
import bipo.tech.duoraapi.chat.domain.ChatMessage;
import bipo.tech.duoraapi.chat.domain.ChatMessageText;
import bipo.tech.duoraapi.chat.domain.ChatPair;
import bipo.tech.duoraapi.chat.domain.ChatRepository;
import bipo.tech.duoraapi.chat.domain.OpeningConditions;
import bipo.tech.duoraapi.events.EventCalendar;
import bipo.tech.duoraapi.events.EventPeriod;
import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.matching.Pairings;
import bipo.tech.duoraapi.trustsafety.Blocking;
import bipo.tech.duoraapi.trustsafety.ChatMessageEvidence;
import bipo.tech.duoraapi.trustsafety.FiledReport;
import bipo.tech.duoraapi.trustsafety.ReportReason;
import bipo.tech.duoraapi.trustsafety.Reports;

/**
 * O chat temporário do par de cada rodada (docs/adr/0021). Quem conversa vem da API publicada do matching, o
 * horário do evento da do events, e o bloqueio e a denúncia com evidência da do trustsafety. Quem não formou par
 * na rodada recebe {@link ChatNotFoundException}, igual a evento ou rodada inexistentes.
 */
@Service
public class ChatService {

    private final Pairings pairings;
    private final EventCalendar calendar;
    private final Blocking blocking;
    private final Reports reports;
    private final ChatRepository chats;
    private final Clock clock;

    public ChatService(Pairings pairings, EventCalendar calendar, Blocking blocking, Reports reports,
            ChatRepository chats, Clock clock) {
        this.pairings = pairings;
        this.calendar = calendar;
        this.blocking = blocking;
        this.reports = reports;
        this.chats = chats;
        this.clock = clock;
    }

    /**
     * O chat de quem chama na rodada, criado na primeira leitura, e se ele aceita mensagens agora. Fechado,
     * continua legível até o expurgo, por qualquer um dos dois.
     *
     * @throws ChatNotFoundException se quem chama não formou par nessa rodada
     */
    @Transactional
    public ChatView chatOf(UUID eventId, int roundNumber, AccountId caller) {
        AccountId partner = partnerOf(eventId, roundNumber, caller);
        Instant now = now();
        var key = new ChatKey(eventId, roundNumber, ChatPair.of(caller, partner));
        EventPeriod period = periodOf(eventId, now);
        Chat chat = chats.findOrAdd(key, Chat.purgeAfter(period.endsAt()), now);
        return new ChatView(chat.id(), chat.acceptsMessages(conditionsOf(key, period, caller, partner)),
                chat.lastSeq());
    }

    /**
     * Grava a mensagem de quem chama, ou devolve a mesma se a Idempotency-Key já gravou esse texto. Com o chat
     * travado: a sequência sai em ordem de commit e o bloqueio já confirmado é sempre visto.
     *
     * @throws ChatNotFoundException se quem chama não formou par nessa rodada
     * @throws bipo.tech.duoraapi.chat.domain.ChatClosedException se o chat não aceita mensagens
     * @throws bipo.tech.duoraapi.chat.domain.IdempotencyKeyReusedException se a chave já gravou outro texto
     */
    @Transactional
    public SendOutcome send(UUID eventId, int roundNumber, AccountId sender, ChatMessageText text,
            UUID idempotencyKey) {
        AccountId partner = partnerOf(eventId, roundNumber, sender);
        Instant now = now();
        var key = new ChatKey(eventId, roundNumber, ChatPair.of(sender, partner));
        EventPeriod period = periodOf(eventId, now);
        Chat chat = chats.lockOrAdd(key, Chat.purgeAfter(period.endsAt()), now);
        Optional<ChatMessage> earlier = chats.findBySenderAndIdempotencyKey(chat.id(), sender, idempotencyKey);
        if (earlier.isPresent()) {
            return new SendOutcome(earlier.get().replayFor(text), false);
        }
        ChatMessage message = chat.send(sender, text, idempotencyKey, now, conditionsOf(key, period, sender, partner));
        chats.record(message);
        return new SendOutcome(message, true);
    }

    /**
     * Até {@code maxPageSize} mensagens depois de {@code afterSeq}, em ordem crescente. Sem chat criado ainda, a
     * página vem vazia.
     *
     * @throws ChatNotFoundException se quem chama não formou par nessa rodada
     */
    @Transactional(readOnly = true)
    public MessagesPage messagesOf(UUID eventId, int roundNumber, AccountId caller, int afterSeq, int maxPageSize) {
        AccountId partner = partnerOf(eventId, roundNumber, caller);
        return chats.find(new ChatKey(eventId, roundNumber, ChatPair.of(caller, partner)))
                .map(chat -> MessagesPage.of(chats.findAfter(chat.id(), afterSeq, maxPageSize + 1), maxPageSize))
                .orElseGet(MessagesPage::empty);
    }

    /**
     * @throws ChatNotFoundException se quem chama não formou par nessa rodada
     * @throws MessageNotFoundException se não há mensagem nessa posição
     */
    @Transactional(readOnly = true)
    public ChatMessage messageOf(UUID eventId, int roundNumber, AccountId caller, int seq) {
        AccountId partner = partnerOf(eventId, roundNumber, caller);
        return chats.find(new ChatKey(eventId, roundNumber, ChatPair.of(caller, partner)))
                .flatMap(chat -> chats.findMessage(chat.id(), seq))
                .orElseThrow(MessageNotFoundException::new);
    }

    /**
     * Denuncia a mensagem do par na posição {@code seq}, guardando uma cópia dela no trustsafety, que sobrevive ao
     * expurgo do chat (docs/adr/0021). Vale com o chat fechado e depois de um bloqueio, até o expurgo. Sem
     * transação própria: a mensagem não muda depois de gravada, e a cota de denúncias é contada em outra conexão
     * antes da gravação, que é um comando só.
     *
     * @param description o relato livre, opcional salvo com {@link ReportReason#OTHER}
     * @throws ChatNotFoundException se quem chama não formou par nessa rodada
     * @throws MessageNotFoundException se não há mensagem nessa posição
     * @throws bipo.tech.duoraapi.chat.domain.OwnMessageNotReportableException se a mensagem é de quem chama
     */
    public FiledReport reportMessage(UUID eventId, int roundNumber, AccountId reporter, int seq, ReportReason reason,
            String description) {
        AccountId partner = partnerOf(eventId, roundNumber, reporter);
        Chat chat = chats.find(new ChatKey(eventId, roundNumber, ChatPair.of(reporter, partner)))
                .orElseThrow(MessageNotFoundException::new);
        ChatMessage message = chats.findMessage(chat.id(), seq).orElseThrow(MessageNotFoundException::new);
        message.ensureReportableBy(reporter);
        var evidence = new ChatMessageEvidence(chat.id(), eventId, roundNumber, seq, message.text().value(),
                message.sentAt());
        return reports.fileWithEvidence(reporter, partner, reason, description, evidence);
    }

    private AccountId partnerOf(UUID eventId, int roundNumber, AccountId caller) {
        return pairings.partnerOf(eventId, roundNumber, caller).orElseThrow(ChatNotFoundException::new);
    }

    /** Com par formado o evento existe; a falta dele é tratada como a do par, sem revelar nada a mais. */
    private EventPeriod periodOf(UUID eventId, Instant now) {
        return calendar.periodOf(eventId, now).orElseThrow(ChatNotFoundException::new);
    }

    private OpeningConditions conditionsOf(ChatKey key, EventPeriod period, AccountId caller, AccountId partner) {
        boolean roundIsCurrent = key.isLatestRound(pairings.latestRoundOf(key.eventId()));
        return new OpeningConditions(period.underway(), roundIsCurrent, blocking.existsBetween(caller, partner));
    }

    /** Em microssegundos, a precisão do timestamptz: a resposta do envio é igual à da leitura depois. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

}
