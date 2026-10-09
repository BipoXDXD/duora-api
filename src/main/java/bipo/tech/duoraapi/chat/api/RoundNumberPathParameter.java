package bipo.tech.duoraapi.chat.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

/** O {@code {number}} da rota, a rodada, documentado igual nas cinco rotas do chat. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Parameter(description = ApiSchemas.ROUND_NUMBER_DESCRIPTION, schema = @Schema(type = "integer", format = "int32",
        minimum = ApiSchemas.FIRST_ROUND, maximum = ApiSchemas.LAST_ROUND))
@interface RoundNumberPathParameter {
}
