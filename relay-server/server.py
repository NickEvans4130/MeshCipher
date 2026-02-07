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
from flask import Flask, jsonify, request, g
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

    existing = RegisteredDevice.query.filter_by(device_id=device_id).first()
    if existing:
        existing.last_seen = datetime.now(timezone.utc)
        if public_key:
            existing.public_key = public_key
        db.session.commit()
        return jsonify({"status": "updated", "id": existing.id})

    device = RegisteredDevice(
        user_id=user_id,
        device_id=device_id,
        public_key=public_key if public_key else None,
    )

    db.session.add(device)
    db.session.commit()

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
    response.headers["Content-Security-Policy"] = "default-src 'none'"
    response.headers["Referrer-Policy"] = "no-referrer"
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
