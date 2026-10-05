package bipo.tech.duoraapi;

import org.springframework.boot.SpringApplication;

/** spring-boot:test-run: PostgreSQL descartável via Testcontainers, sem o banco do compose.yaml. */
public class TestDuoraApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(DuoraApiApplication::main)
                .with(TestcontainersConfiguration.class)
                .withAdditionalProfiles("testcontainers")
                .run(args);
    }

}
