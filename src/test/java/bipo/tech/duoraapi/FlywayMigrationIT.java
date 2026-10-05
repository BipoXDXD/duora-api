package bipo.tech.duoraapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Subir o contexto já prova que o Hibernate (ddl-auto=validate) aceita o schema migrado. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationIT {

    @Autowired
    private Flyway flyway;

    @Test
    void appliesEveryMigrationSuccessfully() {
        var info = flyway.info();

        assertThat(info.pending()).isEmpty();
        assertThat(info.applied())
                .hasSameSizeAs(info.all())
                .allMatch(MigrationInfo::isApplied)
                .noneMatch(migration -> migration.getState().isFailed());
    }

}
