#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
AGENT_BIN="$SCRIPT_DIR/properbackup-agent"
SERVICE_NAME="properbackup-agent"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

if [ ! -f "$AGENT_BIN" ]; then
    echo "ERROR: $AGENT_BIN not found. Run this script from the extracted archive directory."
    exit 1
fi

if [ "$(id -u)" -ne 0 ]; then
    echo "This script requires root privileges."
    echo "Run:"
    echo "  sudo $0"
    exit 1
fi

CONF_DIR="$SCRIPT_DIR/conf.d"
mkdir -p "$CONF_DIR"

cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=ProperBackup Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$AGENT_BIN --headless --conf-dir $CONF_DIR
Restart=always
RestartSec=5
WorkingDirectory=$SCRIPT_DIR
StandardOutput=journal
StandardError=journal
SyslogIdentifier=$SERVICE_NAME

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl start "$SERVICE_NAME"

echo "Service '$SERVICE_NAME' installed and started."
echo "  Status:  systemctl status $SERVICE_NAME"
echo "  Logs:    journalctl -u $SERVICE_NAME -f"
echo "  Stop:    systemctl stop $SERVICE_NAME"
echo "  Disable: systemctl disable $SERVICE_NAME"
