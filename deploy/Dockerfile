# Imagen de ejecución. El binario se compila en el host:  ./gradlew :vdr-service:installDist :did-tools:installDist
FROM eclipse-temurin:21-jre
ARG APP=vdr-service
RUN useradd --system --uid 10001 vdr
COPY ${APP}/build/install/${APP} /app
USER vdr
ENV APP_BIN=/app/bin/${APP}
ENTRYPOINT ["/bin/sh", "-c", "exec $APP_BIN \"$@\"", "--"]
