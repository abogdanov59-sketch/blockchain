FROM gradle:8.5.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :services:blockchain-gateway:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/blockchain-gateway/build/install/blockchain-gateway/ /app/
EXPOSE 8080
ENTRYPOINT ["/app/bin/blockchain-gateway"]
