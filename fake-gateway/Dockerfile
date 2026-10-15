# Build from the repo root:  docker build -f fake-gateway/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY fake-gateway/pom.xml fake-gateway/
COPY service/pom.xml service/
COPY fake-gateway/src fake-gateway/src
RUN mvn -B -q -DskipTests -pl fake-gateway package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/fake-gateway/target/fake-gateway-0.1.0-SNAPSHOT-all.jar gateway.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
