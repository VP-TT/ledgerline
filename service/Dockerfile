# Build from the repo root:  docker build -f service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY fake-gateway/pom.xml fake-gateway/
COPY service/pom.xml service/
COPY fake-gateway/src fake-gateway/src
COPY service/src service/src
RUN mvn -B -q -DskipTests -pl service -am package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/service/target/ledgerline-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
