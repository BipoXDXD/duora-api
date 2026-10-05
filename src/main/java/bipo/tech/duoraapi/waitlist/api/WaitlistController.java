package bipo.tech.duoraapi.waitlist.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bipo.tech.duoraapi.waitlist.application.WaitlistService;
import bipo.tech.duoraapi.waitlist.domain.EmailAddress;
import bipo.tech.duoraapi.waitlist.domain.InvalidEmailAddressException;

@RestController
class WaitlistController {

    private final WaitlistService waitlistService;

    WaitlistController(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    /** Mesma resposta para e-mail novo ou repetido: não revela quem já está na lista. */
    @PostMapping("/api/waitlist")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void join(@RequestBody JoinWaitlistRequest request) {
        waitlistService.join(new EmailAddress(request.email()));
    }

    @GetMapping("/api/admin/waitlist/stats")
    WaitlistStatsResponse stats() {
        return new WaitlistStatsResponse(waitlistService.countEntries());
    }

    @ExceptionHandler(InvalidEmailAddressException.class)
    ProblemDetail handleInvalidEmail(InvalidEmailAddressException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

}
