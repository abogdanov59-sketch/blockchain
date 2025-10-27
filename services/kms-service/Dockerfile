FROM gradle:8.5.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :services:kms-service:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/kms-service/build/install/kms-service/ /app/
EXPOSE 8080
ENTRYPOINT ["/app/bin/kms-service"]
