# Self-Hosting the Relay Server

MeshCipher's relay server is a lightweight Flask application that queues encrypted message blobs between devices. It is zero-knowledge: the server never sees plaintext message content, only opaque encrypted payloads.

You can self-host the relay server to keep all relay infrastructure under your own control.

## Requirements

- Python 3.9+
- PostgreSQL 13+
- A machine reachable by all intended clients (LAN or public internet)

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/NickEvans4130/MeshCipher.git
cd MeshCipher/relay-server
```

### 2. Create a Python virtual environment

```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### 3. Set up PostgreSQL

```bash
sudo -u postgres psql
```

```sql
CREATE USER meshcipher WITH PASSWORD 'your-secure-password';
CREATE DATABASE meshcipher OWNER meshcipher;
\q
```

### 4. Configure environment variables

The server reads all configuration from environment variables. Set these before starting:

| Variable | Default | Description |
|----------|---------|-------------|
| `SECRET_KEY` | `meshcipher-dev-secret-key` | JWT signing key. **Must be changed in production.** Use a random 64+ character string. |
| `DB_USER` | `meshcipher` | PostgreSQL username |
| `DB_PASS` | `meshcipher_password` | PostgreSQL password |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_NAME` | `meshcipher` | PostgreSQL database name |
| `MESSAGE_RETENTION_HOURS` | `72` | Hours to keep delivered messages before cleanup |
| `MAX_QUEUED_PER_RECIPIENT` | `500` | Max queued messages per recipient (prevents abuse) |

Example:

```bash
export SECRET_KEY="$(openssl rand -hex 32)"
export DB_USER="meshcipher"
export DB_PASS="your-secure-password"
export DB_HOST="localhost"
export DB_NAME="meshcipher"
```

### 5. Run the server

**Development:**

```bash
python server.py
```

**Production (gunicorn):**

```bash
gunicorn -w 4 -b 0.0.0.0:5000 server:app
```

### 6. (Optional) Systemd service

Create `/etc/systemd/system/meshcipher-relay.service`:

```ini
[Unit]
Description=MeshCipher Relay Server
After=network.target postgresql.service

[Service]
User=meshcipher
WorkingDirectory=/opt/meshcipher/relay-server
Environment=SECRET_KEY=your-secret-key-here
Environment=DB_USER=meshcipher
Environment=DB_PASS=your-secure-password
Environment=DB_HOST=localhost
Environment=DB_NAME=meshcipher
ExecStart=/opt/meshcipher/relay-server/venv/bin/gunicorn -w 4 -b 0.0.0.0:5000 server:app
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now meshcipher-relay
```

### 7. Verify

```bash
curl http://your-server:5000/api/v1/health
```

Should return `{"status": "healthy", ...}`.

## Connecting Clients

Once the server is running:

1. Open MeshCipher on each device
2. Go to **Settings > Relay Server**
3. Enter the server URL (e.g. `http://192.168.1.100:5000/` or `https://relay.example.com/`)
4. Tap **Save**

Both the sender and recipient must be configured to use the same relay server. Messages sent to relay A will not appear on relay B.

P2P modes (Bluetooth Mesh, WiFi Direct, P2P Tor) do not use the relay server and work regardless of this setting.

## Security Considerations

- **Use HTTPS in production.** Place the server behind a reverse proxy (nginx, Caddy) with TLS termination. The relay sees sender/recipient device IDs in plaintext for routing purposes.
- **Change `SECRET_KEY`** from the default. This key signs JWT authentication tokens.
- **Firewall** the PostgreSQL port (5432) so it is not publicly accessible.
- **The server is zero-knowledge.** It stores encrypted blobs and device IDs. It cannot decrypt message content, see contact lists, or correlate conversation threads.

## What the Relay Server Sees

| Data | Visible to Relay |
|------|-----------------|
| Message plaintext | No (Signal Protocol encrypted) |
| Sender device ID | Yes (for routing) |
| Recipient device ID | Yes (for routing) |
| Sender IP address | Yes (unless using Tor Relay mode) |
| Contact list | No |
| Message timestamps | Yes (queuing metadata) |
| Public keys | Yes (registered during device setup for auth) |
