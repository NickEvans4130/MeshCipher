"""MeshCipher Relay Server - Secure message queuing relay for encrypted messaging."""

import base64
import hashlib
import hmac
import os
import time
import uuid
from datetime import datetime, timedelta, timezone
from functools import wraps

import jwt as pyjwt
from cryptography.hazmat.primitives.asymmetric import ec, utils
from cryptography.hazmat.primitives import hashes, serialization
from flask import Flask, jsonify, request, g, session, redirect, url_for
from flask_sqlalchemy import SQLAlchemy
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address

app = Flask(__name__)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# Secret key for JWT signing - MUST be set via environment variable in production
app.config["SECRET_KEY"] = os.environ.get("SECRET_KEY", "meshcipher-dev-secret-key")

# Maximum request body size (10MB - enough for media envelopes)
app.config["MAX_CONTENT_LENGTH"] = 10 * 1024 * 1024

# Database configuration
DB_USER = os.environ.get("DB_USER", "meshcipher")
DB_PASS = os.environ.get("DB_PASS", "meshcipher_password")
DB_HOST = os.environ.get("DB_HOST", "localhost")
DB_NAME = os.environ.get("DB_NAME", "meshcipher")

app.config["SQLALCHEMY_DATABASE_URI"] = (
    f"postgresql://{DB_USER}:{DB_PASS}@{DB_HOST}/{DB_NAME}"
)
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False

# Message retention (delivered messages cleaned up after this period)
MESSAGE_RETENTION_HOURS = int(os.environ.get("MESSAGE_RETENTION_HOURS", "72"))

# Maximum queued messages per recipient (prevents mailbox bombing)
MAX_QUEUED_PER_RECIPIENT = int(os.environ.get("MAX_QUEUED_PER_RECIPIENT", "500"))

# Admin dashboard password
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", os.environ.get("ADMIN_KEY", ""))

# Challenge expiry
CHALLENGE_EXPIRY_MINUTES = 5

# JWT token expiry
TOKEN_EXPIRY_DAYS = 30

db = SQLAlchemy(app)

# ---------------------------------------------------------------------------
# Rate Limiting
# ---------------------------------------------------------------------------

limiter = Limiter(
    app=app,
    key_func=get_remote_address,
    default_limits=["200 per minute"],
    storage_uri="memory://",
)

# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------


class QueuedMessage(db.Model):
    __tablename__ = "queued_messages"

    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    sender_id = db.Column(db.String(255), nullable=False, index=True)
    recipient_id = db.Column(db.String(255), nullable=False, index=True)
    encrypted_content = db.Column(db.Text, nullable=False)
    content_type = db.Column(db.Integer, nullable=False, default=0)
    queued_at = db.Column(
        db.DateTime, nullable=False, default=lambda: datetime.now(timezone.utc)
    )
    delivered = db.Column(db.Boolean, nullable=False, default=False)
    delivered_at = db.Column(db.DateTime, nullable=True)


class RegisteredDevice(db.Model):
    __tablename__ = "registered_devices"

    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id = db.Column(db.String(255), unique=True, nullable=False, index=True)
    device_id = db.Column(db.String(255), unique=True, nullable=False, index=True)
    public_key = db.Column(db.Text, nullable=True)
    registered_at = db.Column(
        db.DateTime, nullable=False, default=lambda: datetime.now(timezone.utc)
    )
    last_seen = db.Column(
        db.DateTime, nullable=False, default=lambda: datetime.now(timezone.utc)
    )


# In-memory challenge storage (keyed by user_id)
# In production, use Redis or database-backed storage
challenges = {}

with app.app_context():
    db.create_all()

# ---------------------------------------------------------------------------
# Authentication Helpers
# ---------------------------------------------------------------------------


def require_auth(f):
    """Decorator that requires a valid JWT token in the Authorization header."""
    @wraps(f)
    def decorated(*args, **kwargs):
        auth_header = request.headers.get("Authorization", "")

        if not auth_header.startswith("Bearer "):
            return jsonify({"error": "Missing or invalid Authorization header"}), 401

        token = auth_header[7:]

        try:
            payload = pyjwt.decode(
                token, app.config["SECRET_KEY"], algorithms=["HS256"]
            )
            g.user_id = payload["user_id"]
        except pyjwt.ExpiredSignatureError:
            return jsonify({"error": "Token expired"}), 401
        except pyjwt.InvalidTokenError:
            return jsonify({"error": "Invalid token"}), 401

        return f(*args, **kwargs)

    return decorated


def validate_json(*required_fields):
    """Decorator that validates required JSON fields are present."""
    def decorator(f):
        @wraps(f)
        def decorated(*args, **kwargs):
            data = request.get_json(silent=True)
            if not data:
                return jsonify({"error": "Request body must be valid JSON"}), 400
            for field in required_fields:
                if field not in data:
                    return jsonify({"error": f"Missing required field: {field}"}), 400
            g.json = data
            return f(*args, **kwargs)
        return decorated
    return decorator


def sanitize_string(value, max_length=255):
    """Sanitize and truncate string input."""
    if not isinstance(value, str):
        return ""
    return value.strip()[:max_length]


# ---------------------------------------------------------------------------
# Health Check (public, rate limited)
# ---------------------------------------------------------------------------


@app.route("/api/v1/health", methods=["GET"])
@limiter.limit("30 per minute")
def health_check():
    try:
        db.session.execute(db.text("SELECT 1"))
        db_status = "connected"
    except Exception:
        db_status = "error"
        return jsonify({"status": "degraded", "database": db_status}), 503

    try:
        msg_count = QueuedMessage.query.filter_by(delivered=False).count()
        device_count = RegisteredDevice.query.count()
    except Exception:
        return jsonify({"status": "degraded", "database": db_status}), 503

    return jsonify(
        {
            "status": "healthy",
            "database": db_status,
            "queued_messages": msg_count,
            "registered_devices": device_count,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }
    )


# ---------------------------------------------------------------------------
# Authentication Endpoints (public, strictly rate limited)
# ---------------------------------------------------------------------------


@app.route("/api/v1/register", methods=["POST"])
@limiter.limit("10 per minute")
@validate_json("device_id")
def register_device():
    data = g.json
    device_id = sanitize_string(data["device_id"])
    user_id = sanitize_string(data.get("user_id", ""))
    public_key = data.get("public_key", "")

    if not device_id:
        return jsonify({"error": "Invalid device_id"}), 400

    # Check by device_id first, then by user_id
    existing = RegisteredDevice.query.filter_by(device_id=device_id).first()
    if not existing and user_id:
        existing = RegisteredDevice.query.filter_by(user_id=user_id).first()

    if existing:
        existing.last_seen = datetime.now(timezone.utc)
        existing.device_id = device_id
        if user_id:
            existing.user_id = user_id
        if public_key:
            existing.public_key = public_key
        db.session.commit()
        return jsonify({"status": "updated", "id": existing.id})

    device = RegisteredDevice(
        user_id=user_id,
        device_id=device_id,
        public_key=public_key if public_key else None,
    )

    try:
        db.session.add(device)
        db.session.commit()
    except Exception:
        db.session.rollback()
        return jsonify({"error": "Registration failed"}), 500

    return jsonify({"status": "registered", "id": device.id}), 201


@app.route("/api/v1/auth/challenge", methods=["POST"])
@limiter.limit("10 per minute")
@validate_json("userId")
def request_challenge():
    data = g.json
    user_id = sanitize_string(data["userId"])
    public_key = data.get("publicKey", "")

    if not user_id:
        return jsonify({"error": "Invalid userId"}), 400

    challenge = os.urandom(32)
    challenge_b64 = base64.b64encode(challenge).decode()

    challenges[user_id] = {
        "challenge": challenge,
        "public_key": public_key,
        "expires_at": datetime.now(timezone.utc) + timedelta(minutes=CHALLENGE_EXPIRY_MINUTES),
    }

    # Housekeep: remove expired challenges
    now = datetime.now(timezone.utc)
    expired = [k for k, v in challenges.items() if now > v["expires_at"]]
    for k in expired:
        del challenges[k]

    return jsonify({"challenge": challenge_b64})


@app.route("/api/v1/auth/verify", methods=["POST"])
@limiter.limit("10 per minute")
@validate_json("userId", "signature")
def verify_challenge():
    data = g.json
    user_id = sanitize_string(data["userId"])
    signature_b64 = data.get("signature", "")

    if not user_id or not signature_b64:
        return jsonify({"error": "Missing userId or signature"}), 400

    if user_id not in challenges:
        return jsonify({"error": "Challenge not found or expired"}), 404

    stored = challenges[user_id]

    if datetime.now(timezone.utc) > stored["expires_at"]:
        del challenges[user_id]
        return jsonify({"error": "Challenge expired"}), 401

    # Verify ECDSA (P-256/SHA-256) signature against the public key
    # sent during the challenge request. The client signs with
    # SHA256withECDSA on secp256r1 via Android Keystore.
    try:
        signature = base64.b64decode(signature_b64)
        public_key_b64 = stored.get("public_key", "")
        if not public_key_b64:
            del challenges[user_id]
            return jsonify({"error": "No public key on file"}), 401

        public_key_der = base64.b64decode(public_key_b64)
        public_key = serialization.load_der_public_key(public_key_der)

        public_key.verify(
            signature,
            stored["challenge"],
            ec.ECDSA(hashes.SHA256()),
        )
    except Exception:
        del challenges[user_id]
        return jsonify({"error": "Invalid signature"}), 401

    token = pyjwt.encode(
        {
            "user_id": user_id,
            "iat": datetime.now(timezone.utc),
            "exp": datetime.now(timezone.utc) + timedelta(days=TOKEN_EXPIRY_DAYS),
        },
        app.config["SECRET_KEY"],
        algorithm="HS256",
    )

    del challenges[user_id]

    # Update last_seen on the registered device
    device = RegisteredDevice.query.filter_by(user_id=user_id).first()
    if device:
        device.last_seen = datetime.now(timezone.utc)
        db.session.commit()

    return jsonify({"token": token, "expires_in": TOKEN_EXPIRY_DAYS * 24 * 3600})


# ---------------------------------------------------------------------------
# Relay Endpoints (require authentication)
# ---------------------------------------------------------------------------


@app.route("/api/v1/relay/message", methods=["POST"])
@limiter.limit("60 per minute")
@require_auth
@validate_json("sender_id", "recipient_id", "encrypted_content")
def relay_message():
    data = g.json
    sender_id = sanitize_string(data["sender_id"])
    recipient_id = sanitize_string(data["recipient_id"])

    # Verify the sender matches the authenticated user
    if sender_id != g.user_id:
        return jsonify({"error": "Sender ID does not match authenticated user"}), 403

    # Verify encrypted_content is a non-empty string
    encrypted_content = data["encrypted_content"]
    if not isinstance(encrypted_content, str) or len(encrypted_content) == 0:
        return jsonify({"error": "Invalid encrypted_content"}), 400

    # Check recipient mailbox size to prevent mailbox bombing
    queued_count = QueuedMessage.query.filter_by(
        recipient_id=recipient_id, delivered=False
    ).count()
    if queued_count >= MAX_QUEUED_PER_RECIPIENT:
        return jsonify({"error": "Recipient mailbox full"}), 429

    content_type = data.get("content_type", 0)
    if content_type not in (0, 1):
        return jsonify({"error": "Invalid content_type"}), 400

    message = QueuedMessage(
        sender_id=sender_id,
        recipient_id=recipient_id,
        encrypted_content=encrypted_content,
        content_type=content_type,
    )

    db.session.add(message)
    db.session.commit()

    return (
        jsonify(
            {
                "status": "queued",
                "message_id": message.id,
                "queued_at": message.queued_at.isoformat(),
            }
        ),
        201,
    )


@app.route("/api/v1/relay/messages/<recipient_id>", methods=["GET"])
@limiter.limit("60 per minute")
@require_auth
def get_messages(recipient_id):
    recipient_id = sanitize_string(recipient_id)

    # Users can only retrieve their own messages
    if recipient_id != g.user_id:
        return jsonify({"error": "Cannot retrieve messages for another user"}), 403

    messages = (
        QueuedMessage.query.filter_by(recipient_id=recipient_id, delivered=False)
        .order_by(QueuedMessage.queued_at.asc())
        .limit(100)  # Cap per request to prevent huge responses
        .all()
    )

    result = []
    for msg in messages:
        result.append(
            {
                "id": msg.id,
                "sender_id": msg.sender_id,
                "recipient_id": msg.recipient_id,
                "encrypted_content": msg.encrypted_content,
                "content_type": msg.content_type,
                "queued_at": msg.queued_at.isoformat(),
            }
        )

    return jsonify({"messages": result, "count": len(result)})


@app.route("/api/v1/relay/messages/<recipient_id>/ack", methods=["POST"])
@limiter.limit("60 per minute")
@require_auth
@validate_json("message_ids")
def acknowledge_messages(recipient_id):
    data = g.json
    recipient_id = sanitize_string(recipient_id)

    # Users can only acknowledge their own messages
    if recipient_id != g.user_id:
        return jsonify({"error": "Cannot acknowledge messages for another user"}), 403

    message_ids = data["message_ids"]

    # Validate message_ids is a list of strings, cap at 100
    if not isinstance(message_ids, list):
        return jsonify({"error": "message_ids must be a list"}), 400
    message_ids = [sanitize_string(mid, 36) for mid in message_ids[:100]]

    now = datetime.now(timezone.utc)
    count = (
        QueuedMessage.query.filter(
            QueuedMessage.id.in_(message_ids),
            QueuedMessage.recipient_id == recipient_id,
        )
        .update({"delivered": True, "delivered_at": now}, synchronize_session=False)
    )

    db.session.commit()

    return jsonify({"acknowledged": count})


# ---------------------------------------------------------------------------
# Maintenance: Clean up delivered messages
# ---------------------------------------------------------------------------


def cleanup_delivered_messages():
    """Remove delivered messages older than retention period."""
    cutoff = datetime.now(timezone.utc) - timedelta(hours=MESSAGE_RETENTION_HOURS)
    deleted = QueuedMessage.query.filter(
        QueuedMessage.delivered == True,
        QueuedMessage.delivered_at < cutoff,
    ).delete(synchronize_session=False)
    db.session.commit()
    return deleted


@app.route("/api/v1/admin/cleanup", methods=["POST"])
@limiter.limit("1 per minute")
def admin_cleanup():
    """Manual cleanup endpoint. In production, protect with admin auth or run as cron."""
    admin_key = request.headers.get("X-Admin-Key", "")
    expected_key = os.environ.get("ADMIN_KEY", "")

    if not expected_key or not hmac.compare_digest(admin_key, expected_key):
        return jsonify({"error": "Unauthorized"}), 401

    deleted = cleanup_delivered_messages()
    return jsonify({"deleted": deleted})


# ---------------------------------------------------------------------------
# Admin Dashboard
# ---------------------------------------------------------------------------

ADMIN_CSS = """
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { background: #0A0E14; color: #E8EAED; font-family: 'Inter', sans-serif; }
  a { color: #00E676; text-decoration: none; }
  a:hover { text-decoration: underline; }
  .nav { background: #111720; border-bottom: 1px solid #1A2332; padding: 0 24px; display: flex; align-items: center; gap: 24px; height: 56px; }
  .nav-brand { font-family: 'Roboto Mono', monospace; font-weight: 700; font-size: 16px; color: #00E676; letter-spacing: 1px; }
  .nav a { color: #9AA0A6; font-size: 14px; font-weight: 500; padding: 16px 0; border-bottom: 2px solid transparent; }
  .nav a:hover, .nav a.active { color: #E8EAED; border-bottom-color: #00E676; text-decoration: none; }
  .nav .spacer { flex: 1; }
  .nav .logout { color: #5F6368; font-size: 13px; }
  .container { max-width: 1200px; margin: 0 auto; padding: 32px 24px; }
  h1 { font-size: 24px; font-weight: 700; margin-bottom: 24px; }
  .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 32px; }
  .stat-card { background: #111720; border: 1px solid #1A2332; border-radius: 8px; padding: 20px; }
  .stat-label { font-family: 'Roboto Mono', monospace; font-size: 11px; color: #5F6368; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 8px; }
  .stat-value { font-size: 32px; font-weight: 700; color: #00E676; }
  .stat-value.warn { color: #FFA726; }
  table { width: 100%; border-collapse: collapse; background: #111720; border-radius: 8px; overflow: hidden; }
  thead { background: #1A2332; }
  th { font-family: 'Roboto Mono', monospace; font-size: 11px; color: #5F6368; text-transform: uppercase; letter-spacing: 1px; padding: 12px 16px; text-align: left; }
  td { padding: 12px 16px; border-top: 1px solid #1A2332; font-size: 13px; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  tr:hover td { background: #1A2332; }
  .mono { font-family: 'Roboto Mono', monospace; font-size: 12px; }
  .badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
  .badge-green { background: rgba(0,230,118,0.15); color: #00E676; }
  .badge-yellow { background: rgba(255,167,38,0.15); color: #FFA726; }
  .badge-red { background: rgba(244,67,54,0.15); color: #F44336; }
  .btn { display: inline-block; padding: 6px 14px; border-radius: 6px; font-size: 13px; font-weight: 500; border: none; cursor: pointer; font-family: 'Inter', sans-serif; }
  .btn-danger { background: rgba(244,67,54,0.15); color: #F44336; border: 1px solid rgba(244,67,54,0.3); }
  .btn-danger:hover { background: rgba(244,67,54,0.25); }
  .btn-green { background: #00E676; color: #0A0E14; }
  .btn-green:hover { background: #00C853; }
  .login-wrap { display: flex; align-items: center; justify-content: center; min-height: 100vh; }
  .login-box { background: #111720; border: 1px solid #1A2332; border-radius: 12px; padding: 40px; width: 100%; max-width: 380px; }
  .login-box h1 { text-align: center; font-size: 20px; }
  .login-box .brand { text-align: center; font-family: 'Roboto Mono', monospace; color: #00E676; font-size: 14px; letter-spacing: 2px; margin-bottom: 8px; }
  .form-group { margin-bottom: 16px; }
  .form-group label { display: block; font-size: 12px; color: #9AA0A6; margin-bottom: 6px; font-family: 'Roboto Mono', monospace; text-transform: uppercase; letter-spacing: 1px; }
  .form-group input { width: 100%; padding: 10px 14px; background: #0A0E14; border: 1px solid #1A2332; border-radius: 6px; color: #E8EAED; font-size: 14px; font-family: 'Inter', sans-serif; outline: none; }
  .form-group input:focus { border-color: #00E676; }
  .error-msg { background: rgba(244,67,54,0.1); border: 1px solid rgba(244,67,54,0.3); color: #F44336; padding: 10px; border-radius: 6px; font-size: 13px; margin-bottom: 16px; }
  .success-msg { background: rgba(0,230,118,0.1); border: 1px solid rgba(0,230,118,0.3); color: #00E676; padding: 10px; border-radius: 6px; font-size: 13px; margin-bottom: 16px; }
  .empty { text-align: center; padding: 48px; color: #5F6368; font-size: 14px; }
  .toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
  @media (max-width: 768px) {
    .stats { grid-template-columns: 1fr 1fr; }
    td, th { padding: 8px 10px; font-size: 12px; }
  }
</style>
"""

ADMIN_HEAD = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>MeshCipher Admin</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Roboto+Mono:wght@400;500;700&display=swap" rel="stylesheet">
""" + ADMIN_CSS + "</head>"


def admin_nav(active="dashboard"):
    """Render the admin navigation bar."""
    def cls(name):
        return ' class="active"' if active == name else ""
    return f"""
    <nav class="nav">
        <span class="nav-brand">MESHCIPHER</span>
        <a href="/admin"{cls("dashboard")}>Dashboard</a>
        <a href="/admin/devices"{cls("devices")}>Devices</a>
        <a href="/admin/messages"{cls("messages")}>Messages</a>
        <span class="spacer"></span>
        <a href="/admin/logout" class="logout">Logout</a>
    </nav>"""


def admin_required(f):
    """Decorator that requires admin session for dashboard pages."""
    @wraps(f)
    def decorated(*args, **kwargs):
        if not session.get("admin"):
            return redirect(url_for("admin_login"))
        return f(*args, **kwargs)
    return decorated


@app.route("/admin/login", methods=["GET", "POST"])
@limiter.limit("10 per minute")
def admin_login():
    if not ADMIN_PASSWORD:
        return ADMIN_HEAD + """<body><div class="login-wrap"><div class="login-box">
            <div class="brand">MESHCIPHER</div><h1>Admin Dashboard</h1>
            <br><div class="error-msg">ADMIN_PASSWORD or ADMIN_KEY environment variable is not set. Set it to enable the admin dashboard.</div>
            </div></div></body></html>"""

    error = ""
    if request.method == "POST":
        pw = request.form.get("password", "")
        if hmac.compare_digest(pw, ADMIN_PASSWORD):
            session["admin"] = True
            return redirect(url_for("admin_dashboard"))
        error = '<div class="error-msg">Invalid password.</div>'

    return ADMIN_HEAD + f"""<body><div class="login-wrap"><div class="login-box">
        <div class="brand">MESHCIPHER</div>
        <h1>Admin Dashboard</h1>
        <br>{error}
        <form method="POST">
            <div class="form-group">
                <label>Password</label>
                <input type="password" name="password" autofocus placeholder="Enter admin password">
            </div>
            <button type="submit" class="btn btn-green" style="width:100%;padding:12px;">Login</button>
        </form>
    </div></div></body></html>"""


@app.route("/admin/logout")
def admin_logout():
    session.pop("admin", None)
    return redirect(url_for("admin_login"))


@app.route("/admin")
@admin_required
def admin_dashboard():
    device_count = RegisteredDevice.query.count()
    queued_count = QueuedMessage.query.filter_by(delivered=False).count()
    delivered_count = QueuedMessage.query.filter_by(delivered=True).count()
    total_messages = queued_count + delivered_count

    try:
        db.session.execute(db.text("SELECT 1"))
        db_status = "connected"
    except Exception:
        db_status = "error"

    return ADMIN_HEAD + f"""<body>
    {admin_nav("dashboard")}
    <div class="container">
        <h1>Dashboard</h1>
        <div class="stats">
            <div class="stat-card">
                <div class="stat-label">Registered Devices</div>
                <div class="stat-value">{device_count}</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Queued Messages</div>
                <div class="stat-value{' warn' if queued_count > 50 else ''}">{queued_count}</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Delivered Messages</div>
                <div class="stat-value">{delivered_count}</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Total Messages</div>
                <div class="stat-value">{total_messages}</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Database</div>
                <div class="stat-value" style="font-size:20px;">
                    <span class="badge {'badge-green' if db_status == 'connected' else 'badge-red'}">{db_status.upper()}</span>
                </div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Server Time (UTC)</div>
                <div class="stat-value mono" style="font-size:16px;">{datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S')}</div>
            </div>
        </div>

        <div class="toolbar">
            <h2 style="font-size:16px;">Maintenance</h2>
        </div>
        <form method="POST" action="/admin/cleanup" style="margin-bottom:16px;">
            <button type="submit" class="btn btn-green" onclick="return confirm('Clean up delivered messages older than {MESSAGE_RETENTION_HOURS}h?')">
                Cleanup Delivered Messages (&gt;{MESSAGE_RETENTION_HOURS}h)
            </button>
        </form>
    </div>
    </body></html>"""


@app.route("/admin/devices")
@admin_required
def admin_devices():
    devices = RegisteredDevice.query.order_by(RegisteredDevice.registered_at.desc()).all()

    rows = ""
    for d in devices:
        pk_display = (d.public_key[:24] + "...") if d.public_key and len(d.public_key) > 24 else (d.public_key or "-")
        reg = d.registered_at.strftime("%Y-%m-%d %H:%M") if d.registered_at else "-"
        seen = d.last_seen.strftime("%Y-%m-%d %H:%M") if d.last_seen else "-"
        rows += f"""<tr>
            <td class="mono">{d.id[:8]}...</td>
            <td class="mono" title="{d.user_id}">{d.user_id[:16]}...</td>
            <td class="mono" title="{d.device_id}">{d.device_id[:12]}...</td>
            <td class="mono" title="{d.public_key or ''}">{pk_display}</td>
            <td>{reg}</td>
            <td>{seen}</td>
            <td>
                <form method="POST" action="/admin/devices/{d.id}/delete" style="display:inline;">
                    <button type="submit" class="btn btn-danger" onclick="return confirm('Delete this device?')">Delete</button>
                </form>
            </td>
        </tr>"""

    if not devices:
        rows = '<tr><td colspan="7" class="empty">No registered devices.</td></tr>'

    return ADMIN_HEAD + f"""<body>
    {admin_nav("devices")}
    <div class="container">
        <div class="toolbar">
            <h1>Devices <span style="font-size:14px;color:#5F6368;">({len(devices)})</span></h1>
        </div>
        <table>
            <thead><tr>
                <th>ID</th><th>User ID</th><th>Device ID</th><th>Public Key</th><th>Registered</th><th>Last Seen</th><th>Actions</th>
            </tr></thead>
            <tbody>{rows}</tbody>
        </table>
    </div>
    </body></html>"""


@app.route("/admin/devices/<device_id>/delete", methods=["POST"])
@admin_required
def admin_delete_device(device_id):
    device = RegisteredDevice.query.get(device_id)
    if device:
        db.session.delete(device)
        db.session.commit()
    return redirect(url_for("admin_devices"))


@app.route("/admin/messages")
@admin_required
def admin_messages():
    page = request.args.get("page", 1, type=int)
    per_page = 50
    show = request.args.get("show", "queued")

    if show == "all":
        query = QueuedMessage.query
    elif show == "delivered":
        query = QueuedMessage.query.filter_by(delivered=True)
    else:
        query = QueuedMessage.query.filter_by(delivered=False)

    total = query.count()
    messages = query.order_by(QueuedMessage.queued_at.desc()).offset((page - 1) * per_page).limit(per_page).all()

    filter_btns = ""
    for label, val in [("Queued", "queued"), ("Delivered", "delivered"), ("All", "all")]:
        active = "btn-green" if show == val else "btn-danger"
        style = 'style="background:#1A2332;color:#9AA0A6;border:1px solid #1A2332;"' if show != val else ""
        filter_btns += f' <a href="/admin/messages?show={val}" class="btn {active}" {style}>{label}</a>'

    rows = ""
    for m in messages:
        queued = m.queued_at.strftime("%Y-%m-%d %H:%M") if m.queued_at else "-"
        delivered_at = m.delivered_at.strftime("%Y-%m-%d %H:%M") if m.delivered_at else "-"
        status_badge = '<span class="badge badge-green">DELIVERED</span>' if m.delivered else '<span class="badge badge-yellow">QUEUED</span>'
        ct = "TEXT" if m.content_type == 0 else "MEDIA"
        content_preview = m.encrypted_content[:32] + "..." if len(m.encrypted_content) > 32 else m.encrypted_content
        rows += f"""<tr>
            <td class="mono">{m.id[:8]}...</td>
            <td class="mono" title="{m.sender_id}">{m.sender_id[:16]}...</td>
            <td class="mono" title="{m.recipient_id}">{m.recipient_id[:16]}...</td>
            <td><span class="badge {'badge-green' if m.content_type == 0 else 'badge-yellow'}">{ct}</span></td>
            <td>{status_badge}</td>
            <td>{queued}</td>
            <td>{delivered_at}</td>
            <td>
                <form method="POST" action="/admin/messages/{m.id}/delete" style="display:inline;">
                    <button type="submit" class="btn btn-danger" onclick="return confirm('Delete this message?')">Delete</button>
                </form>
            </td>
        </tr>"""

    if not messages:
        rows = '<tr><td colspan="8" class="empty">No messages found.</td></tr>'

    # Pagination
    total_pages = max(1, (total + per_page - 1) // per_page)
    pagination = ""
    if total_pages > 1:
        pagination = '<div style="margin-top:16px;text-align:center;">'
        if page > 1:
            pagination += f'<a href="/admin/messages?show={show}&page={page-1}" class="btn" style="background:#1A2332;color:#9AA0A6;">Previous</a> '
        pagination += f'<span class="mono" style="color:#5F6368;margin:0 12px;">Page {page} / {total_pages}</span>'
        if page < total_pages:
            pagination += f' <a href="/admin/messages?show={show}&page={page+1}" class="btn" style="background:#1A2332;color:#9AA0A6;">Next</a>'
        pagination += '</div>'

    return ADMIN_HEAD + f"""<body>
    {admin_nav("messages")}
    <div class="container">
        <div class="toolbar">
            <h1>Messages <span style="font-size:14px;color:#5F6368;">({total})</span></h1>
            <div>{filter_btns}</div>
        </div>
        <table>
            <thead><tr>
                <th>ID</th><th>Sender</th><th>Recipient</th><th>Type</th><th>Status</th><th>Queued At</th><th>Delivered At</th><th>Actions</th>
            </tr></thead>
            <tbody>{rows}</tbody>
        </table>
        {pagination}
    </div>
    </body></html>"""


@app.route("/admin/messages/<message_id>/delete", methods=["POST"])
@admin_required
def admin_delete_message(message_id):
    message = QueuedMessage.query.get(message_id)
    if message:
        db.session.delete(message)
        db.session.commit()
    return redirect(url_for("admin_messages"))


@app.route("/admin/cleanup", methods=["POST"])
@admin_required
def admin_dashboard_cleanup():
    deleted = cleanup_delivered_messages()
    return redirect(url_for("admin_dashboard"))


# ---------------------------------------------------------------------------
# Error Handlers
# ---------------------------------------------------------------------------


@app.errorhandler(413)
def request_entity_too_large(e):
    return jsonify({"error": "Request body too large (max 10MB)"}), 413


@app.errorhandler(429)
def ratelimit_handler(e):
    return jsonify({"error": "Rate limit exceeded", "retry_after": e.description}), 429


@app.errorhandler(404)
def not_found(e):
    return jsonify({"error": "Not found"}), 404


@app.errorhandler(405)
def method_not_allowed(e):
    return jsonify({"error": "Method not allowed"}), 405


@app.errorhandler(500)
def internal_error(e):
    return jsonify({"error": "Internal server error"}), 500


# ---------------------------------------------------------------------------
# Security Headers
# ---------------------------------------------------------------------------


@app.after_request
def set_security_headers(response):
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["X-XSS-Protection"] = "1; mode=block"
    response.headers["Cache-Control"] = "no-store"
    response.headers["Referrer-Policy"] = "no-referrer"
    # Admin pages need inline styles and Google Fonts
    if request.path.startswith("/admin"):
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; "
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
            "font-src https://fonts.gstatic.com; "
            "script-src 'unsafe-inline'"
        )
    else:
        response.headers["Content-Security-Policy"] = "default-src 'none'"
    # Remove server header
    response.headers.pop("Server", None)
    return response


# ---------------------------------------------------------------------------
# Entry Point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    # Development only. In production, use gunicorn:
    # gunicorn -w 4 -b 0.0.0.0:5000 server:app
    app.run(host="0.0.0.0", port=5000)
