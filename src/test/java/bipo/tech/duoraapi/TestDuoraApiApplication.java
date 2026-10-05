package bipo.tech.duoraapi;

import org.springframework.boot.SpringApplication;

public class TestDuoraApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(DuoraApiApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
