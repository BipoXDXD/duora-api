package bipo.tech.duoraapi.profiles.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import bipo.tech.duoraapi.profiles.domain.Region;
import io.swagger.v3.oas.models.media.Schema;

/**
 * Os códigos de região aceitos vêm do enum {@link Region}, e não de uma lista repetida numa anotação
 * (docs/adr/0012). O null entra na lista porque a região pode estar vazia e, no PATCH, null é pedido
 * válido (que o servidor recusa com 400, porque a região não pode ser apagada).
 */
@Configuration(proxyBeanMethods = false)
class ProfileOpenApiConfiguration {

    private static final List<String> SCHEMAS_WITH_REGION = List.of("ProfileResponse", "EditProfileRequest");

    @Bean
    OpenApiCustomizer regionCodes() {
        List<Object> codes = new ArrayList<>(Arrays.stream(Region.values()).map(Region::code).toList());
        codes.add(null);
        return openApi -> SCHEMAS_WITH_REGION.forEach(name -> regionOf(openApi.getComponents().getSchemas().get(name))
                .setEnum(codes));
    }

    @SuppressWarnings("unchecked") // a API de modelos do swagger expõe Schema cru nas propriedades
    private static Schema<Object> regionOf(Schema<?> schema) {
        return (Schema<Object>) schema.getProperties().get("region");
    }

}
