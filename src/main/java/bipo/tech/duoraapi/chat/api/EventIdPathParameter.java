package bipo.tech.duoraapi.chat.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

/** O {@code {eventId}} da rota, documentado igual nas cinco rotas do chat. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Parameter(description = ApiSchemas.EVENT_ID_DESCRIPTION, schema = @Schema(type = "string", format = "uuid",
        minLength = ApiSchemas.UUID_LENGTH, maxLength = ApiSchemas.UUID_LENGTH))
@interface EventIdPathParameter {
}
