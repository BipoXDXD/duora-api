package bipo.tech.duoraapi.chat.api;

import static bipo.tech.duoraapi.chat.api.ApiSchemas.PROBLEM_JSON;
import static bipo.tech.duoraapi.chat.api.ApiSchemas.PROBLEM_SCHEMA;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import bipo.tech.duoraapi.chat.application.ChatService;
import bipo.tech.duoraapi.chat.application.SendOutcome;
import bipo.tech.duoraapi.chat.domain.ChatMessageText;
import bipo.tech.duoraapi.config.AccountRateLimit;
import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.Reports;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * O chat temporário do par de uma rodada, como sub-recurso singular da rodada (docs/adr/0021): não há id de
 * pessoa nem de chat na rota, então a rota só alcança o chat de quem chama. O cliente faz polling da lista
 * com o cursor afterSeq. O envio gasta o limite da conta antes de abrir a transação que trava o chat,
 * inclusive na repetição com a mesma Idempotency-Key.
 */
@RestController
@Tag(name = "chat", description = "Chat temporário entre as duas pessoas que o sorteio juntou numa rodada")
class ChatController {

    static final String CHAT_PATH = "/api/events/{eventId}/rounds/{number}/chat";
    static final String MESSAGES_PATH = CHAT_PATH + "/messages";
    static final String MESSAGE_PATH = MESSAGES_PATH + "/{seq}";
    static final String REPORT_PATH = MESSAGE_PATH + ":report";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /**
     * A leitura sem ":": sem o filtro, GET em {@code {seq}:report} cairia na leitura com a posição "1:report" (400),
     * e não no 405 de método não aceito pela ação.
     */
    private static final String MESSAGE_ROUTE = MESSAGES_PATH + "/{seq:[^:]+}";

    private static final String REPORTS_PATH = "/api/reports/";

    private static final String NOT_IN_THE_PAIR = "Quem chama não formou par nessa rodada: ficou de fora, não "
            + "estava no sorteio, ou a rodada ou o evento não existem";
    private static final String BAD_PATH = "Id que não é UUID, ou número fora de " + ApiSchemas.FIRST_ROUND + " a "
            + ApiSchemas.LAST_ROUND;

    private final ChatService chats;
    private final AccountRateLimit rateLimit;

    ChatController(ChatService chats, @Qualifier(ChatRateLimitConfiguration.BEAN_NAME) AccountRateLimit rateLimit) {
        this.chats = chats;
        this.rateLimit = rateLimit;
    }

    @GetMapping(CHAT_PATH)
    @Operation(operationId = "getMyRoundChat", summary = "Lê o chat com o par da rodada",
            description = "Se o chat aceita mensagens agora e a posição da última. Abre ao sorteio e fecha para "
                    + "envio quando a rodada seguinte começa ou o evento acaba; fechado, continua legível pelos "
                    + "dois até 24 h depois do fim do evento. Leia de novo ao abrir a tela, ao voltar à aba e "
                    + "ao receber 409 no envio.")
    @ApiResponse(responseCode = "200", description = "O chat de quem chama na rodada")
    @ApiResponse(responseCode = "400", description = BAD_PATH,
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = NOT_IN_THE_PAIR,
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ChatResponse chat(
            @EventIdPathParameter @PathVariable UUID eventId,
            @RoundNumberPathParameter @PathVariable int number,
            AccountId account) {
        return ChatResponse.of(chats.chatOf(eventId, ChatParameters.roundNumber(number), account));
    }

    @GetMapping(MESSAGES_PATH)
    @Operation(operationId = "listMyRoundChatMessages", summary = "Lista as mensagens do chat da rodada",
            description = "As mensagens depois de afterSeq, em ordem crescente de posição. Peça de novo com o "
                    + "nextAfterSeq até ele vir null; depois, faça polling com afterSeq igual à maior posição "
                    + "já vista. Uma lacuna nas posições quer dizer mensagem perdida: peça de novo.")
    @ApiResponse(responseCode = "200", description = "Uma página das mensagens")
    @ApiResponse(responseCode = "400", description = BAD_PATH + ", afterSeq fora de 0 a " + ApiSchemas.MAX_MESSAGES
            + " ou maxPageSize fora de 1 a " + ChatParameters.MAX_PAGE_SIZE,
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = NOT_IN_THE_PAIR,
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ChatMessagesResponse messages(
            @EventIdPathParameter @PathVariable UUID eventId,
            @RoundNumberPathParameter @PathVariable int number,
            AccountId account,
            @Parameter(description = "Só mensagens com posição maior que esta; ausente na primeira leitura",
                    schema = @Schema(type = "integer", format = "int32", minimum = "0",
                            maximum = ApiSchemas.MAX_MESSAGES, defaultValue = "0"))
            @RequestParam(name = "afterSeq", required = false) String afterSeqText,
            @Parameter(description = "Quantas mensagens no máximo nesta página",
                    schema = @Schema(type = "integer", format = "int32", minimum = "1",
                            maximum = "" + ChatParameters.MAX_PAGE_SIZE,
                            defaultValue = "" + ChatParameters.DEFAULT_PAGE_SIZE))
            @RequestParam(name = "maxPageSize", required = false) String maxPageSizeText) {
        int roundNumber = ChatParameters.roundNumber(number);
        int afterSeq = ChatParameters.afterSeq(afterSeqText);
        int maxPageSize = ChatParameters.maxPageSize(maxPageSizeText);
        return ChatMessagesResponse.of(chats.messagesOf(eventId, roundNumber, account, afterSeq, maxPageSize),
                account);
    }

    @GetMapping(MESSAGE_ROUTE)
    @Operation(operationId = "getMyRoundChatMessage", summary = "Lê uma mensagem do chat da rodada",
            description = "A mensagem numa posição do chat de quem chama.")
    @ApiResponse(responseCode = "200", description = "A mensagem")
    @ApiResponse(responseCode = "400", description = BAD_PATH + ", ou posição fora de 1 a " + ApiSchemas.MAX_MESSAGES,
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Não há mensagem nessa posição, ou quem chama não formou par "
            + "nessa rodada",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ChatMessageResponse message(
            @EventIdPathParameter @PathVariable UUID eventId,
            @RoundNumberPathParameter @PathVariable int number,
            @Parameter(description = "A posição da mensagem no chat", schema = @Schema(type = "integer",
                    format = "int32", minimum = "1", maximum = ApiSchemas.MAX_MESSAGES))
            @PathVariable int seq,
            AccountId account) {
        int roundNumber = ChatParameters.roundNumber(number);
        var message = chats.messageOf(eventId, roundNumber, account, ChatParameters.seq(seq));
        return ChatMessageResponse.of(message, account);
    }

    /** 201 com Location na primeira vez; 200 com a mesma mensagem nas repetições com a mesma chave e texto. */
    @PostMapping(MESSAGES_PATH)
    @Operation(operationId = "sendRoundChatMessage", summary = "Envia uma mensagem ao par da rodada",
            description = "Gere a Idempotency-Key ao criar o rascunho e reutilize-a em todo reenvio (rede caiu, "
                    + "timeout, aba recarregada): a mesma chave com o mesmo texto devolve a mesma mensagem "
                    + "(200), mesmo depois de o chat fechar. Com o chat fechado, pelo motivo que for, a resposta "
                    + "é 409 com reason CHAT_CLOSED; leia o chat de novo. Cada chamada, repetida ou não, gasta "
                    + "o limite da conta: " + ChatRateLimitProperties.DEFAULT_CAPACITY + " por minuto, repostas aos "
                    + "poucos.")
    @ApiResponse(responseCode = "201", description = "A mensagem gravada",
            headers = @Header(name = "Location", required = true, description = "Endereço da mensagem",
                    schema = @Schema(type = "string", format = "uri", maxLength = ApiSchemas.LOCATION_MAX_LENGTH)))
    @ApiResponse(responseCode = "200",
            description = "A mensagem que a mesma Idempotency-Key já tinha gravado, com o mesmo texto")
    @ApiResponse(responseCode = "400",
            description = BAD_PATH + ", Idempotency-Key ausente ou que não é UUID, texto vazio, com mais de " + ChatMessageText.MAX_LENGTH
                    + " caracteres ou com caractere proibido, JSON malformado ou campo desconhecido",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = NOT_IN_THE_PAIR,
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "409",
            description = "O chat não aceita mensagens (reason CHAT_CLOSED), ou a Idempotency-Key já gravou "
                    + "outro texto (reason IDEMPOTENCY_KEY_REUSED); nada foi gravado",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "429", description = "Limite de mensagens desta conta esgotado; nada foi gravado",
            headers = @Header(name = "Retry-After", required = true,
                    description = "Segundos até a próxima mensagem ficar disponível",
                    schema = @Schema(type = "integer", format = "int64", minimum = "0",
                            maximum = AccountRateLimit.MAX_RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "503",
            description = "Outra mensagem ao mesmo chat demorou além do teto, ou o limite desta conta não pôde ser "
                    + "contado; nada foi gravado, e reenviar com a mesma Idempotency-Key é seguro",
            headers = @Header(name = "Retry-After", required = true, description = "Segundos até tentar de novo",
                    schema = @Schema(type = "integer", format = "int32",
                            minimum = ChatExceptionHandler.RETRY_AFTER_SECONDS,
                            maximum = ChatExceptionHandler.RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ResponseEntity<ChatMessageResponse> send(
            @EventIdPathParameter @PathVariable UUID eventId,
            @RoundNumberPathParameter @PathVariable int number,
            @Parameter(description = "Gerada pelo cliente para o rascunho e repetida em todo reenvio dele",
                    schema = @Schema(type = "string", format = "uuid", minLength = ApiSchemas.UUID_LENGTH,
                            maxLength = ApiSchemas.UUID_LENGTH))
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) UUID idempotencyKey,
            AccountId account,
            @Valid @RequestBody SendMessageRequest request) {
        int roundNumber = ChatParameters.roundNumber(number);
        var text = new ChatMessageText(request.text());
        rateLimit.consume(account);
        SendOutcome outcome = chats.send(eventId, roundNumber, account, text, idempotencyKey);
        var body = ChatMessageResponse.of(outcome.message(), account);
        if (outcome.created()) {
            URI location = UriComponentsBuilder.fromPath(MESSAGE_PATH)
                    .buildAndExpand(eventId, roundNumber, outcome.message().seq()).toUri();
            return ResponseEntity.created(location).body(body);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Ação sobre a mensagem que cria uma denúncia no trustsafety (docs/adr/0005, 0021): 201 com Location na
     * denúncia, legível depois por getMyReport. Quem não está no par, a posição vazia e a própria mensagem são
     * recusadas antes de gastar a cota de denúncias.
     */
    @PostMapping(REPORT_PATH)
    @Operation(operationId = "reportRoundChatMessage", summary = "Denuncia uma mensagem do par da rodada",
            description = "Cria uma denúncia contra o par, com uma cópia da mensagem guardada para a moderação: a "
                    + "cópia continua depois que o chat é apagado, 24 h após o fim do evento. Vale com o chat "
                    + "fechado e depois de bloquear o par. Só a mensagem do outro pode ser denunciada. A cota é a "
                    + "mesma de fileReport: " + Reports.DEFAULT_DAILY_LIMIT + " denúncias por dia, somando as duas rotas. "
                    + "Denunciar não bloqueia: "
                    + "para isso, chame blockAccount.")
    @ApiResponse(responseCode = "201", description = "A denúncia criada",
            headers = @Header(name = "Location", required = true, description = "Endereço da denúncia criada",
                    schema = @Schema(type = "string", format = "uri", maxLength = ApiSchemas.LOCATION_MAX_LENGTH)))
    @ApiResponse(responseCode = "400",
            description = BAD_PATH + ", posição fora de 1 a " + ApiSchemas.MAX_MESSAGES + ", mensagem enviada por quem chama, "
                    + "motivo OTHER sem "
                    + "descrição, descrição inválida, JSON malformado ou campo desconhecido",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Não há mensagem nessa posição, ou quem chama não formou par "
            + "nessa rodada",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "429", description = "Cota diária de denúncias desta conta esgotada; nada foi gravado",
            headers = @Header(name = "Retry-After", required = true,
                    description = "Segundos até a próxima denúncia ficar disponível",
                    schema = @Schema(type = "integer", format = "int64", minimum = "0",
                            maximum = AccountRateLimit.MAX_RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "503", description = "Cota indisponível; a denúncia é recusada",
            headers = @Header(name = "Retry-After", required = true, description = "Segundos até tentar de novo",
                    schema = @Schema(type = "integer", format = "int32",
                            minimum = AccountRateLimit.UNAVAILABLE_RETRY_AFTER_SECONDS,
                            maximum = AccountRateLimit.UNAVAILABLE_RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ResponseEntity<ChatMessageReportResponse> report(
            @EventIdPathParameter @PathVariable UUID eventId,
            @RoundNumberPathParameter @PathVariable int number,
            @Parameter(description = "A posição da mensagem do par no chat", schema = @Schema(type = "integer",
                    format = "int32", minimum = "1", maximum = ApiSchemas.MAX_MESSAGES))
            @PathVariable int seq,
            AccountId account,
            @Valid @RequestBody ReportChatMessageRequest request) {
        int roundNumber = ChatParameters.roundNumber(number);
        var report = chats.reportMessage(eventId, roundNumber, account, ChatParameters.seq(seq), request.reason(),
                request.description());
        return ResponseEntity.created(URI.create(REPORTS_PATH + report.id()))
                .body(ChatMessageReportResponse.of(report));
    }

}
