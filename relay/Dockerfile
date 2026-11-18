# Built by CI after `gradlew -p relay installDist`.
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source=https://github.com/kayloh20/kylie-mail \
      org.opencontainers.image.description="Kylie Mail push relay"

RUN useradd --system --uid 10001 --no-create-home relay \
    && mkdir /data && chown relay /data

COPY build/install/relay /app

USER relay
ENV DATA_DIR=/data \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/app/bin/relay"]
