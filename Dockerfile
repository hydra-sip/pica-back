# Imagen de la API. Compila con el JDK y deja solo el jar sobre un JRE.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Las dependencias van en una capa propia: si solo cambia el código, no se vuelven a bajar
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B --no-transfer-progress dependency:go-offline

COPY src/ src/
RUN ./mvnw -B --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source=https://github.com/hydra-sip/pica-back
WORKDIR /app

RUN groupadd --system pica && useradd --system --gid pica --no-create-home pica
COPY --from=build /app/target/plataforma-pica-*.jar app.jar
USER pica

# La JVM toma el 75 % de la memoria del contenedor, no la de la máquina
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
