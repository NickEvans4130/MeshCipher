#!/bin/bash
set -e

echo "=== MeshCipher Relay Server Setup ==="

# Install system dependencies
echo "[1/5] Installing system packages..."
sudo apt update
sudo apt install -y postgresql postgresql-contrib python3 python3-pip python3-venv

# Setup PostgreSQL
echo "[2/5] Configuring PostgreSQL..."
sudo -u postgres psql -tc "SELECT 1 FROM pg_roles WHERE rolname='meshcipher'" | grep -q 1 || \
    sudo -u postgres psql -c "CREATE USER meshcipher WITH PASSWORD 'meshcipher_password';"
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='meshcipher'" | grep -q 1 || \
    sudo -u postgres psql -c "CREATE DATABASE meshcipher OWNER meshcipher;"
sudo -u postgres psql -c "GRANT ALL PRIVILEGES ON DATABASE meshcipher TO meshcipher;" 2>/dev/null || true

# Create virtual environment
echo "[3/5] Setting up Python environment..."
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

# Create systemd service
echo "[4/5] Creating systemd service..."
INSTALL_DIR=$(pwd)
USER=$(whoami)

sudo tee /etc/systemd/system/meshcipher-relay.service > /dev/null << EOF
[Unit]
Description=MeshCipher Relay Server
After=network.target postgresql.service

[Service]
Type=simple
User=${USER}
WorkingDirectory=${INSTALL_DIR}
Environment=PATH=${INSTALL_DIR}/venv/bin:/usr/bin
ExecStart=${INSTALL_DIR}/venv/bin/python3 ${INSTALL_DIR}/server.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

# Enable and start service
echo "[5/5] Starting service..."
sudo systemctl daemon-reload
sudo systemctl enable meshcipher-relay
sudo systemctl start meshcipher-relay

echo ""
echo "=== Setup Complete ==="
echo "Server running on port 5000"
echo "Check status: sudo systemctl status meshcipher-relay"
echo "View logs:    journalctl -u meshcipher-relay -f"
echo ""
IP=$(hostname -I | awk '{print $1}')
echo "Health check: http://${IP}:5000/api/v1/health"
