FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="daniel@properbackup.com"
LABEL description="ProperBackup Agent — encrypted differential backup agent"

RUN addgroup -S pb && adduser -S pb -G pb

COPY build/install/properbackup-agent-jvm/ /opt/properbackup-agent/
COPY docker-entrypoint.sh /opt/properbackup-agent/docker-entrypoint.sh

RUN mkdir -p /data/state /data/conf.d \
 && chown -R pb:pb /data /opt/properbackup-agent \
 && chmod +x /opt/properbackup-agent/docker-entrypoint.sh

USER pb

ENV PROPERBACKUP_USER_ID=""
ENV PROPERBACKUP_SERVER_ID=""
ENV PROPERBACKUP_BUFFER_URL="http://host.docker.internal:7100"
ENV PROPERBACKUP_UPLOAD_TOKEN=""
ENV PROPERBACKUP_ENCRYPTION_PASSWORD=""
ENV PROPERBACKUP_POLL_INTERVAL="120"

VOLUME ["/data/backup-source", "/data/state", "/data/conf.d"]

ENTRYPOINT ["/opt/properbackup-agent/docker-entrypoint.sh"]
