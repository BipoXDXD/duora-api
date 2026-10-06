package bipo.tech.duoraapi.trustsafety.api;

/** O pageToken não é um que a API gerou. */
class InvalidPageTokenException extends RuntimeException {

    InvalidPageTokenException() {
        super("pageToken is not valid");
    }

}
