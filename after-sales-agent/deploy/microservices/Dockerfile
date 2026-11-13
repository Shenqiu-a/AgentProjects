FROM maven:3.10.0-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/ backend/
ARG SERVICE
RUN test -n "$SERVICE" && mvn -B -ntp -f backend/pom.xml -pl "$SERVICE" -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
ARG SERVICE
COPY --from=build /workspace/backend/${SERVICE}/target/${SERVICE}-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
