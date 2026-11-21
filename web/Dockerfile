# Built by CI after `gradlew -p web installDist`.
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source=https://github.com/kayloh20/kylie-mail \
      org.opencontainers.image.description="Kylie Mail on the web"

RUN useradd --system --uid 10001 --no-create-home web \
    && mkdir /data && chown web /data && chmod 700 /data

COPY build/install/web /app

USER web
ENV DATA_DIR=/data \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/app/bin/web"]
