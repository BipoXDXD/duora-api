package bipo.tech.duoraapi.waitlist.api;

import io.swagger.v3.oas.annotations.media.Schema;

record WaitlistStatsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = WaitlistController.INT64_MAX,
                description = "Inscrições na lista")
        long total) {
}
