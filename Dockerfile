FROM eclipse-temurin:25-jre-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-yaml ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --chmod=644 build/libs/uzelok-ai-0.0.1-SNAPSHOT.jar /app/uzelok.jar

USER 1000:1000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/uzelok.jar"]
