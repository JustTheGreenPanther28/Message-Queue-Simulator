# One Dockerfile for every backend service: docker build --build-arg SERVICE=chat-service ./backend
FROM maven:3.9-eclipse-temurin-17 AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${SERVICE} -am package -DskipTests && \
    cp ${SERVICE}/target/${SERVICE}-*.jar /app.jar

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
USER app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
