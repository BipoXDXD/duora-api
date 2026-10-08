package bipo.tech.duoraapi.connections.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import bipo.tech.duoraapi.connections.application.ConnectionsPage;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Envelope de lista da docs/adr/0005. Cada item é só o id da outra conta e a data (docs/adr/0019); o que
 * mais mostrar da pessoa depende de uma API publicada do profiles, que ainda não existe.
 *
 * @param nextPageToken null na última página
 */
record ConnectionsResponse(
        @ArraySchema(maxItems = ConnectionController.MAX_PAGE_SIZE,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Conexões da mais recente para a mais antiga"))
        List<Item> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                pattern = ConnectionPageToken.PATTERN, maxLength = ConnectionPageToken.MAX_LENGTH,
                description = "Token da próxima página, ou null na última")
        String nextPageToken) {

    @Schema(name = "Connection")
    record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                    maxLength = ApiSchemas.UUID_LENGTH, description = "Id da outra conta")
            UUID accountId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                    description = "Quando a conexão se formou")
            Instant connectedAt) {
    }

    static ConnectionsResponse of(ConnectionsPage page) {
        var items = page.connections().stream()
                .map(connection -> new Item(connection.account().value(), connection.connectedAt()))
                .toList();
        return new ConnectionsResponse(items, page.nextPosition().map(ConnectionPageToken::encode).orElse(null));
    }

}
