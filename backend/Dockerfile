FROM maven:3.9-eclipse-temurin-21 AS build
# Keep the wrapper on the ZIP distribution checked by distributionSha256Sum.
RUN apt-get update && apt-get install -y --no-install-recommends unzip
WORKDIR /workspace
COPY .mvn ./.mvn
COPY mvnw pom.xml ./
COPY src ./src
RUN sh ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/hookfault-backend-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
