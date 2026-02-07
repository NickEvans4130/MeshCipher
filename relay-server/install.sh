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

# Generate secrets if not already set
echo "[4/6] Generating secrets..."
SECRET_KEY=$(python3 -c "import secrets; print(secrets.token_hex(32))")
ADMIN_KEY=$(python3 -c "import secrets; print(secrets.token_hex(32))")

ENV_FILE="${INSTALL_DIR}/.env"
if [ ! -f "$ENV_FILE" ]; then
    cat > "$ENV_FILE" << ENVEOF
SECRET_KEY=${SECRET_KEY}
ADMIN_KEY=${ADMIN_KEY}
DB_USER=meshcipher
DB_PASS=meshcipher_password
DB_HOST=localhost
DB_NAME=meshcipher
MESSAGE_RETENTION_HOURS=72
MAX_QUEUED_PER_RECIPIENT=500
ENVEOF
    chmod 600 "$ENV_FILE"
    echo "  Created .env with generated secrets"
else
    echo "  .env already exists, skipping"
fi

# Create systemd service
echo "[5/6] Creating systemd service..."
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
EnvironmentFile=${INSTALL_DIR}/.env
Environment=PATH=${INSTALL_DIR}/venv/bin:/usr/bin
ExecStart=${INSTALL_DIR}/venv/bin/gunicorn -w 4 -b 0.0.0.0:5000 server:app
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

# Enable and start service
echo "[6/6] Starting service..."
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
