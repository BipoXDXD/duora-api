package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A descrição de cada rota com limite por conta cita o padrão de application.properties ("60 por hora"). O texto
 * vem de uma constante, porque a anotação só aceita constante; este teste falha quando o padrão muda no
 * application.properties e a constante não (ou o contrário). Lê o docs/openapi.json, que o OpenApiContractIT
 * garante ser o que a aplicação gera.
 */
class RateLimitDescriptionsTest {

    private static final Path APPLICATION_PROPERTIES = Path.of("src", "main", "resources", "application.properties");
    private static final Path COMMITTED_SPEC = Path.of("docs", "openapi.json");
    private static final List<String> HTTP_METHODS = List.of("get", "put", "post", "delete", "patch");

    private static final Map<Duration, String> PERIOD_WORDS = Map.of(
            Duration.ofMinutes(1), "minuto",
            Duration.ofHours(1), "hora",
            Duration.ofDays(1), "dia");

    private static Properties settings;
    private static JsonNode spec;

    @BeforeAll
    static void load() throws IOException {
        settings = new Properties();
        try (Reader reader = Files.newBufferedReader(APPLICATION_PROPERTIES, StandardCharsets.ISO_8859_1)) {
            settings.load(reader);
        }
        spec = JsonMapper.builder().build().readTree(COMMITTED_SPEC.toFile());
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            sendRoundChatMessage,       duora.chat.message-rate-limit,           ''
            reportRoundChatMessage,     duora.trustsafety.report-rate-limit,     ' denúncias'
            fileReport,                 duora.trustsafety.report-rate-limit,     ' denúncias'
            blockAccount,               duora.trustsafety.block-rate-limit,      ''
            unblockAccount,             duora.trustsafety.block-rate-limit,      ''
            editMyProfile,              duora.profiles.edit-rate-limit,          ''
            registerForEvent,           duora.events.registration-rate-limit,    ''
            cancelMyRegistration,       duora.events.registration-rate-limit,    ''
            startRound,                 duora.matching.round-rate-limit,         ''
            decideAboutMyPartner,       duora.connections.decision-rate-limit,   ''
            """)
    void descriptionCitesTheConfiguredLimit(String operationId, String limitPrefix, String noun) {
        int capacity = Integer.parseInt(settings.getProperty(limitPrefix + ".capacity"));
        Duration period = Duration.parse(settings.getProperty(limitPrefix + ".period"));

        assertThat(PERIOD_WORDS).as("período de %s", limitPrefix).containsKey(period);
        assertThat(descriptionOf(operationId))
                .contains(capacity + noun + " por " + PERIOD_WORDS.get(period));
    }

    private static String descriptionOf(String operationId) {
        return spec.at("/paths").valueStream()
                .flatMap(item -> HTTP_METHODS.stream().filter(item::has).map(item::get))
                .filter(operation -> operationId.equals(operation.path("operationId").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("operação ausente na spec: " + operationId))
                .path("description").asString();
    }

}
