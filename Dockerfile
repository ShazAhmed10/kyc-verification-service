# Multi-stage so `docker compose up` works from a clean clone with no local JDK or Maven.
# Build arg selects which module's jar ends up in the runtime image - both services share this file.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# Copy poms first so dependency resolution is cached independently of source changes.
COPY pom.xml .
COPY verification-service/pom.xml verification-service/
COPY vendor-simulator/pom.xml vendor-simulator/
RUN mvn -B -q dependency:go-offline -DskipTests || true
COPY . .
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre AS runtime
ARG MODULE=verification-service
WORKDIR /app
COPY --from=build /src/${MODULE}/target/*.jar app.jar
# Containers should die on OOM rather than thrash, and the heap should follow the container limit
# rather than a hardcoded -Xmx that ignores it.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
