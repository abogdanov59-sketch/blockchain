FROM gradle:8.5.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :services:audit-service:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/audit-service/build/install/audit-service/ /app/
EXPOSE 8080
ENTRYPOINT ["/app/bin/audit-service"]
