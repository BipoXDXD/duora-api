# syntax=docker/dockerfile:1
# Imagem da API (docs/adr/0008): compila com o JDK e roda só com o JRE, em camadas, sem root.
# As bases ficam fixadas por digest; o Dependabot propõe a atualização.

FROM eclipse-temurin:25-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4 AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline
COPY src/main/ src/main/
# Os testes rodam no CI antes da imagem; aqui só se empacota.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package -Dmaven.test.skip=true \
    && cp target/duora-api-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
RUN groupadd --system --gid 10001 duora \
    && useradd --system --uid 10001 --gid duora --no-create-home --shell /usr/sbin/nologin duora
WORKDIR /app
# Da camada que menos muda para a que mais muda: um deploy só de código reaproveita as dependências.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001
EXPOSE 8080
# Forma exec: a JVM é o PID 1 e recebe o SIGTERM do graceful shutdown.
# MaxRAMPercentage: heap pelo limite de memória do container, com folga para metaspace e threads.
# ExitOnOutOfMemoryError: sem heap, o processo cai e a plataforma repõe a réplica (let it crash).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
