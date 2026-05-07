#!/bin/sh
set -e

CONF_DIR="/data/conf.d"
GLOBAL_YML="$CONF_DIR/global.yml"

# Generate global.yml from environment variables if it doesn't exist
if [ ! -f "$GLOBAL_YML" ]; then
  echo "Generating $GLOBAL_YML from environment variables..."

  if [ -z "$PROPERBACKUP_USER_ID" ] || [ -z "$PROPERBACKUP_SERVER_ID" ]; then
    echo "ERROR: PROPERBACKUP_USER_ID and PROPERBACKUP_SERVER_ID are required."
    echo "Mount a .env file or pass them as environment variables."
    exit 1
  fi

  cat > "$GLOBAL_YML" <<EOF
agent:
  user_id: "$PROPERBACKUP_USER_ID"
  server_id: "$PROPERBACKUP_SERVER_ID"
  buffer_url: "${PROPERBACKUP_BUFFER_URL:-http://host.docker.internal:7100}"
  upload_token: "${PROPERBACKUP_UPLOAD_TOKEN:-dev-token-local}"
  encryption_password: "${PROPERBACKUP_ENCRYPTION_PASSWORD:-change-me}"
  state_dir: "/data/state"
  poll_interval_seconds: ${PROPERBACKUP_POLL_INTERVAL:-120}

default_excludes:
  - "*.log"
  - ".git"
  - "node_modules"
  - "__pycache__"
  - ".DS_Store"
EOF

  echo "Generated $GLOBAL_YML"
fi

# Generate service config for /data/backup-source if no service configs exist
SERVICE_COUNT=$(ls "$CONF_DIR"/*.yml 2>/dev/null | grep -v global.yml | wc -l)
if [ "$SERVICE_COUNT" -eq 0 ]; then
  cat > "$CONF_DIR/backup.yml" <<EOF
service: backup
enabled: true
paths:
  - /data/backup-source
exclude:
  - "*.log"
  - "*.tmp"
  - "cache"
EOF
  echo "Generated default service config: $CONF_DIR/backup.yml"
fi

exec /opt/properbackup-agent/bin/properbackup-agent-jvm \
  --config "$GLOBAL_YML" \
  --conf-dir "$CONF_DIR" \
  --headless \
  "$@"
