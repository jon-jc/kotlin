FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S roam && adduser -S -G roam roam
WORKDIR /opt/roam
# Build with ./gradlew :server:installDist, then docker build -f server/Dockerfile .
COPY --chown=roam:roam server/build/install/server/ ./
RUN chmod 0555 /opt/roam/bin/server
USER roam
EXPOSE 8080
ENV JAVA_OPTS="-Xms64m -Xmx384m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["/opt/roam/bin/server"]
