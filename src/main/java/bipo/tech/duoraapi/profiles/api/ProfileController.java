package bipo.tech.duoraapi.profiles.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.profiles.application.OutdatedProfileVersionException;
import bipo.tech.duoraapi.profiles.application.ProfileService;
import bipo.tech.duoraapi.profiles.application.ProfileView;
import bipo.tech.duoraapi.profiles.domain.BirthDateAlreadySetException;
import bipo.tech.duoraapi.profiles.domain.InvalidProfileException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Perfil do próprio usuário, um recurso singular sob /api/me: existe para toda conta, vazio até a
 * primeira edição, e não há rota com o id de outro perfil (docs/adr/0011). A edição exige If-Match
 * com o ETag lido, para uma aba não apagar em silêncio o que outra gravou.
 */
@RestController
@Tag(name = "profile", description = "Perfil do usuário logado")
class ProfileController {

    static final String PATH = "/api/me/profile";

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_SCHEMA = "#/components/schemas/ProblemDetail";
    private static final String ETAG_DESCRIPTION = "Versão do perfil, para mandar no If-Match da próxima edição";
    /** Como {@link #VERSION_ETAG}: a versão entre aspas, sem prefixo W/. */
    private static final String ETAG_PATTERN = "^\"[0-9]{1,18}\"$";
    private static final int ETAG_MAX_LENGTH = 20;

    /**
     * Um ETag forte só com a versão, escrita como o servidor a escreve (sem zero à esquerda: "00" não é o ETag
     * "0"); fraco (W/), curinga, lista ou outro formato não casa com versão nenhuma. São 18 dígitos no máximo
     * para caber em long.
     */
    private static final Pattern VERSION_ETAG = Pattern.compile("\"(0|[1-9]\\d{0,17})\"");

    private final ProfileService profiles;

    ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping(PATH)
    @Operation(operationId = "getMyProfile", summary = "Lê o próprio perfil",
            description = "Toda conta tem um perfil, vazio até a primeira edição. O ETag da resposta vai no "
                    + "If-Match da edição.")
    @ApiResponse(responseCode = "200", description = "O perfil, com a versão no ETag",
            headers = @Header(name = HttpHeaders.ETAG, required = true, description = ETAG_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = ETAG_PATTERN, maxLength = ETAG_MAX_LENGTH)))
    ResponseEntity<ProfileResponse> myProfile(AccountId account) {
        return withETag(profiles.profileOf(account.value()));
    }

    @PatchMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "editMyProfile", summary = "Edita o próprio perfil",
            description = "Merge patch de um nível: campo ausente não muda, null apaga e valor troca. Nome, data de "
                    + "nascimento e região não podem ser apagados; a data de nascimento só pode ser informada uma vez "
                    + "e precisa ser de maior de idade. A edição é inteira ou nada.")
    @ApiResponse(responseCode = "200", description = "O perfil editado, com a nova versão no ETag",
            headers = @Header(name = HttpHeaders.ETAG, required = true, description = ETAG_DESCRIPTION,
                    schema = @Schema(type = "string", pattern = ETAG_PATTERN, maxLength = ETAG_MAX_LENGTH)))
    @ApiResponse(responseCode = "400",
            description = "Valor inválido, campo obrigatório apagado, JSON malformado ou campo desconhecido",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "409", description = "A data de nascimento já foi informada e não muda",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "412",
            description = "O If-Match não é o ETag atual: o perfil mudou desde a leitura, ou o valor não é um ETag",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "428", description = "Falta o If-Match",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ResponseEntity<ProfileResponse> editMyProfile(AccountId account,
            // required na spec, embora não no Spring: sem ele a resposta é o 428 documentado, e não um 400 genérico.
            @Parameter(name = HttpHeaders.IF_MATCH, in = ParameterIn.HEADER, required = true,
                    description = "O ETag da última leitura do perfil",
                    schema = @Schema(type = "string", pattern = ETAG_PATTERN, maxLength = ETAG_MAX_LENGTH))
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody EditProfileRequest request) {
        long readVersion = readVersionIn(ifMatch);
        return withETag(profiles.edit(account.value(), readVersion, request.toChanges()));
    }

    @ExceptionHandler(InvalidProfileException.class)
    ProblemDetail handleInvalidProfile(InvalidProfileException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(BirthDateAlreadySetException.class)
    ProblemDetail handleBirthDateAlreadySet(BirthDateAlreadySetException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    /** A conferência da versão no serviço, ou a versão otimista do JPA numa edição simultânea. */
    @ExceptionHandler({OutdatedProfileVersionException.class, ObjectOptimisticLockingFailureException.class})
    ProblemDetail handleOutdatedVersion() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED,
                "the profile changed since it was read; read it again and reapply the changes");
    }

    private static long readVersionIn(String ifMatch) {
        if (ifMatch == null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match with the ETag of the profile is required");
        }
        Matcher etag = VERSION_ETAG.matcher(ifMatch);
        if (!etag.matches()) {
            throw new OutdatedProfileVersionException();
        }
        return Long.parseLong(etag.group(1));
    }

    private static ResponseEntity<ProfileResponse> withETag(ProfileView profile) {
        return ResponseEntity.ok()
                .eTag(Long.toString(profile.version()))
                .body(ProfileResponse.of(profile));
    }

}
