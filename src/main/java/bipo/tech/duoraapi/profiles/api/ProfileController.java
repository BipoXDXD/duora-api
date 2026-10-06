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

/**
 * Perfil do próprio usuário, um recurso singular sob /api/me: existe para toda conta, vazio até a
 * primeira edição, e não há rota com o id de outro perfil (docs/adr/0011). A edição exige If-Match
 * com o ETag lido, para uma aba não apagar em silêncio o que outra gravou.
 */
@RestController
class ProfileController {

    static final String PATH = "/api/me/profile";

    /** Um ETag forte só com a versão; fraco (W/), curinga, lista ou outro formato não casa com versão nenhuma. */
    private static final Pattern VERSION_ETAG = Pattern.compile("\"(\\d{1,18})\"");

    private final ProfileService profiles;

    ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping(PATH)
    ResponseEntity<ProfileResponse> myProfile(AccountId account) {
        return withETag(profiles.profileOf(account.value()));
    }

    @PatchMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ProfileResponse> editMyProfile(AccountId account,
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
