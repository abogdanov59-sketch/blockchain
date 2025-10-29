FROM gradle:8.5.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :services:sandbox-service:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/sandbox-service/build/install/sandbox-service/ /app/
EXPOSE 8080
ENTRYPOINT ["/app/bin/sandbox-service"]
