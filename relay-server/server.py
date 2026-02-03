"""MeshCipher Relay Server - Message queuing relay for encrypted messaging."""

import base64
import os
import uuid
from datetime import datetime, timedelta, timezone

import jwt as pyjwt
from flask import Flask, jsonify, request
from flask_sqlalchemy import SQLAlchemy

app = Flask(__name__)
app.config["SECRET_KEY"] = os.environ.get("SECRET_KEY", "meshcipher-dev-secret-key")

# Temporary challenge storage
challenges = {}

# Database configuration
DB_USER = os.environ.get("DB_USER", "meshcipher")
DB_PASS = os.environ.get("DB_PASS", "meshcipher_password")
DB_HOST = os.environ.get("DB_HOST", "localhost")
DB_NAME = os.environ.get("DB_NAME", "meshcipher")

app.config["SQLALCHEMY_DATABASE_URI"] = (
    f"postgresql://{DB_USER}:{DB_PASS}@{DB_HOST}/{DB_NAME}"
)
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False

db = SQLAlchemy(app)


class QueuedMessage(db.Model):
    __tablename__ = "queued_messages"

    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    sender_id = db.Column(db.String(255), nullable=False)
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
    device_id = db.Column(db.String(255), unique=True, nullable=False, index=True)
    public_key = db.Column(db.Text, nullable=True)
    registered_at = db.Column(
        db.DateTime, nullable=False, default=lambda: datetime.now(timezone.utc)
    )
    last_seen = db.Column(
        db.DateTime, nullable=False, default=lambda: datetime.now(timezone.utc)
    )


with app.app_context():
    db.create_all()


@app.route("/api/v1/health", methods=["GET"])
def health_check():
    try:
        db.session.execute(db.text("SELECT 1"))
        db_status = "connected"
    except Exception:
        db_status = "error"

    msg_count = QueuedMessage.query.filter_by(delivered=False).count()
    device_count = RegisteredDevice.query.count()

    return jsonify(
        {
            "status": "healthy",
            "database": db_status,
            "queued_messages": msg_count,
            "registered_devices": device_count,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }
    )


@app.route("/api/v1/relay/message", methods=["POST"])
def relay_message():
    data = request.get_json()

    if not data:
        return jsonify({"error": "No JSON body provided"}), 400

    required_fields = ["sender_id", "recipient_id", "encrypted_content"]
    for field in required_fields:
        if field not in data:
            return jsonify({"error": f"Missing required field: {field}"}), 400

    message = QueuedMessage(
        sender_id=data["sender_id"],
        recipient_id=data["recipient_id"],
        encrypted_content=data["encrypted_content"],
        content_type=data.get("content_type", 0),
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
def get_messages(recipient_id):
    messages = (
        QueuedMessage.query.filter_by(recipient_id=recipient_id, delivered=False)
        .order_by(QueuedMessage.queued_at.asc())
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
def acknowledge_messages(recipient_id):
    data = request.get_json()

    if not data or "message_ids" not in data:
        return jsonify({"error": "Missing message_ids"}), 400

    now = datetime.now(timezone.utc)
    count = (
        QueuedMessage.query.filter(
            QueuedMessage.id.in_(data["message_ids"]),
            QueuedMessage.recipient_id == recipient_id,
        )
        .update({"delivered": True, "delivered_at": now}, synchronize_session=False)
    )

    db.session.commit()

    return jsonify({"acknowledged": count})


@app.route("/api/v1/register", methods=["POST"])
def register_device():
    data = request.get_json()

    if not data or "device_id" not in data:
        return jsonify({"error": "Missing device_id"}), 400

    existing = RegisteredDevice.query.filter_by(device_id=data["device_id"]).first()
    if existing:
        existing.last_seen = datetime.now(timezone.utc)
        if "public_key" in data:
            existing.public_key = data["public_key"]
        db.session.commit()
        return jsonify({"status": "updated", "id": existing.id})

    device = RegisteredDevice(
        device_id=data["device_id"],
        public_key=data.get("public_key"),
    )

    db.session.add(device)
    db.session.commit()

    return jsonify({"status": "registered", "id": device.id}), 201


@app.route("/api/v1/auth/challenge", methods=["POST"])
def request_challenge():
    data = request.get_json()
    if not data or "userId" not in data:
        return jsonify({"error": "Missing userId"}), 400

    user_id = data["userId"]
    public_key = data.get("publicKey", "")

    challenge = os.urandom(32)
    challenge_b64 = base64.b64encode(challenge).decode()

    challenges[user_id] = {
        "challenge": challenge,
        "public_key": public_key,
        "expires_at": datetime.now(timezone.utc) + timedelta(minutes=5),
    }

    return jsonify({"challenge": challenge_b64})


@app.route("/api/v1/auth/verify", methods=["POST"])
def verify_challenge():
    data = request.get_json()
    if not data:
        return jsonify({"error": "No JSON body"}), 400

    user_id = data.get("userId")
    signature_b64 = data.get("signature")

    if not user_id or not signature_b64:
        return jsonify({"error": "Missing userId or signature"}), 400

    if user_id not in challenges:
        return jsonify({"error": "Challenge not found"}), 404

    stored = challenges[user_id]

    if datetime.now(timezone.utc) > stored["expires_at"]:
        del challenges[user_id]
        return jsonify({"error": "Challenge expired"}), 401

    # TODO: Verify ECDSA signature with stored public key
    # For now, accept all valid challenge responses

    token = pyjwt.encode(
        {
            "user_id": user_id,
            "exp": datetime.now(timezone.utc) + timedelta(days=30),
        },
        app.config["SECRET_KEY"],
        algorithm="HS256",
    )

    del challenges[user_id]

    return jsonify({"token": token, "expires_in": 30 * 24 * 3600})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
