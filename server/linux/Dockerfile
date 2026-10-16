# PrivateTracker Server in a container. The image takes the distribution Gradle builds, since building
# inside it would need the Android SDK (the project also holds the app):
#
#   ./gradlew :server:linux:installDist
#   docker build -t privatetracker-server server/linux
#
# See README.md for running it, pairing phones and backups.
FROM eclipse-temurin:21-jre-noble

# A user of its own; /data holds the keys and server.db, so only that user may enter it.
RUN useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin privatetracker \
    && install -d -o privatetracker -g privatetracker -m 700 /data

COPY build/install/privatetracker-server /opt/privatetracker-server

ENV PRIVATETRACKER_DATA=/data \
    PATH=/opt/privatetracker-server/bin:$PATH

USER privatetracker
VOLUME /data
# 8787: the trackers' protocol. 8788: the web app (PRIVATETRACKER_WEB_PORT=0 turns it off).
EXPOSE 8787 8788

# Asks the running server over its admin socket; exits 3 when it does not answer.
HEALTHCHECK --interval=60s --timeout=10s --start-period=30s CMD ["privatetracker-server", "status", "--quiet"]

ENTRYPOINT ["privatetracker-server"]
CMD ["serve"]
