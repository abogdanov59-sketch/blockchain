FROM gradle:8.5.0-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle :services:data-vault-service:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/services/data-vault-service/build/install/data-vault-service/ /app/
EXPOSE 8080
ENTRYPOINT ["/app/bin/data-vault-service"]
