FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
WORKDIR /app
COPY relay/target/relay-0.1.0-SNAPSHOT.jar /app/relay.jar
USER 10001:10001
EXPOSE 8443
ENTRYPOINT ["java", "-XX:-HeapDumpOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=/dev/null", "-jar", "/app/relay.jar"]