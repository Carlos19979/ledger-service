# syntax=docker/dockerfile:1.7
# One Dockerfile for the three services: pick one with --build-arg MODULE_DIR=ledger/app (default),
# consumers/activity-projection or consumers/fraud-alerts.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG MODULE_DIR=ledger/app
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew ":$(echo "${MODULE_DIR}" | tr / :):bootJar" --no-daemon --quiet \
 && find "${MODULE_DIR}/build/libs" -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
USER app
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
