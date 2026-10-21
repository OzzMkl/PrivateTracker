# PrivateTracker Relay in a container, for a host with a public address and a domain. The image takes
# the distribution Gradle builds:
#
#   ./gradlew :server:relay:installDist
#   docker build -t privatetracker-relay server/relay
#
# See README.md for DNS, the list of servers and the firewall.
FROM eclipse-temurin:21-jre-noble

RUN useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin privatetracker \
    && install -d -o privatetracker -g privatetracker -m 700 /data

COPY build/install/privatetracker-relay /opt/privatetracker-relay

# 8443 inside: no privilege needed to listen there. Publish it as 443 (see docker-compose.yml).
ENV PRIVATETRACKER_RELAY_DATA=/data \
    PRIVATETRACKER_RELAY_PORT=8443 \
    PATH=/opt/privatetracker-relay/bin:$PATH

USER privatetracker
VOLUME /data
EXPOSE 8443

# Asks the relay as a server would; exits 3 when it does not answer.
HEALTHCHECK --interval=60s --timeout=15s --start-period=20s CMD ["privatetracker-relay", "health", "--quiet"]

ENTRYPOINT ["privatetracker-relay"]
CMD ["serve"]
