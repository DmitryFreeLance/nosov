FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN mkdir -p /app/data

COPY --from=build /app/target/speedcallerbot-1.0.0.jar /app/speedcallerbot.jar

ENV DB_PATH=/app/data/speedcallerbot.db

CMD ["java", "-jar", "/app/speedcallerbot.jar"]
