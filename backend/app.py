"""Small local authentication API. PostgreSQL stores users and hashed sessions."""
import hashlib
import os
import re
import secrets

import psycopg
from psycopg.rows import dict_row
from flask import Flask, jsonify, request
from werkzeug.security import check_password_hash, generate_password_hash

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 4096


def database():
    return psycopg.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        dbname=os.environ["POSTGRES_DB"],
        user=os.environ["POSTGRES_USER"],
        password=os.environ["POSTGRES_PASSWORD"],
        row_factory=dict_row,
    )


def initialize():
    with database() as db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                username VARCHAR(32) NOT NULL UNIQUE
                    CHECK (username ~ '^[a-z0-9]{1,32}$'),
                name VARCHAR(100) NOT NULL CHECK (length(trim(name)) > 0),
                password_hash TEXT NOT NULL
            )
        """)
        db.execute("""
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '1 day'
            )
        """)
        db.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ")
        db.execute("ALTER TABLE sessions ADD COLUMN IF NOT EXISTS previous_login_at TIMESTAMPTZ")


def session(db, user):
    token = secrets.token_urlsafe(32)
    previous = db.execute("SELECT last_login_at FROM users WHERE id = %s FOR UPDATE",
                          (user["id"],)).fetchone()["last_login_at"]
    db.execute("UPDATE users SET last_login_at = clock_timestamp() WHERE id = %s",
               (user["id"],))
    db.execute("DELETE FROM sessions WHERE expires_at <= now()")
    db.execute("""
        INSERT INTO sessions (token_hash, user_id, previous_login_at, expires_at)
        VALUES (%s, %s, %s, now() + interval '30 days')
    """, (hashlib.sha256(token.encode()).hexdigest(), user["id"], previous))
    return jsonify(token=token, user={
        "id": user["id"], "username": user["username"], "name": user["name"],
        "last_logged_on": int(previous.timestamp() * 1000) if previous else None
    })


def credentials():
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        return None
    if not isinstance(data.get("username"), str) or not isinstance(data.get("password"), str):
        return None
    return data


@app.get("/health")
def health():
    with database() as db:
        db.execute("SELECT 1")
    return jsonify(status="ok")


@app.post("/signup")
def signup():
    data = credentials()
    if data is None:
        return jsonify(error="Username and password are required."), 400
    username = data["username"]
    password = data["password"]
    name = data.get("name")
    if not re.fullmatch(r"[A-Za-z0-9]{1,32}", username):
        return jsonify(error="Username must contain only letters and numbers (1–32 characters)."), 400
    if (not 8 <= len(password) <= 128 or not re.search(r"[A-Za-z]", password)
            or not re.search(r"[0-9]", password)):
        return jsonify(error="Password needs 8–128 characters, at least one letter and one number."), 400
    if not isinstance(name, str) or not 1 <= len(name.strip()) <= 100:
        return jsonify(error="Name is required (up to 100 characters)."), 400
    try:
        with database() as db:
            user = db.execute(
                "INSERT INTO users (username, name, password_hash) VALUES (%s, %s, %s) RETURNING *",
                (username.lower(), name.strip(), generate_password_hash(password, method="scrypt")),
            ).fetchone()
            return session(db, user), 201
    except psycopg.errors.UniqueViolation:
        return jsonify(error="That username is already taken."), 409


@app.post("/signin")
def signin():
    data = credentials()
    if data is None:
        return jsonify(error="Username and password are required."), 400
    if (not re.fullmatch(r"[A-Za-z0-9]{1,32}", data["username"])
            or not 1 <= len(data["password"]) <= 128):
        return jsonify(error="Incorrect username or password."), 401
    with database() as db:
        user = db.execute("SELECT * FROM users WHERE username = %s",
                          (data["username"].lower(),)).fetchone()
        # Check a hash even for unknown users to reduce timing differences.
        valid = check_password_hash(user["password_hash"] if user else DUMMY_HASH,
                                    data["password"])
        if not user or not valid:
            return jsonify(error="Incorrect username or password."), 401
        return session(db, user)


@app.get("/me")
def me():
    header = request.headers.get("Authorization", "")
    token = header.removeprefix("Bearer ") if header.startswith("Bearer ") else ""
    if not token:
        return jsonify(error="Sign in required."), 401
    with database() as db:
        user = db.execute("""
            SELECT users.id, users.username, users.name, sessions.previous_login_at FROM sessions
            JOIN users ON users.id = sessions.user_id
            WHERE token_hash = %s AND expires_at > now()
        """, (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
    if not user:
        return jsonify(error="Session expired. Please sign in again."), 401
    previous = user.pop("previous_login_at")
    user["last_logged_on"] = int(previous.timestamp() * 1000) if previous else None
    return jsonify(user=user)


@app.post("/signout")
def signout():
    header = request.headers.get("Authorization", "")
    token = header.removeprefix("Bearer ") if header.startswith("Bearer ") else ""
    if token:
        with database() as db:
            db.execute("DELETE FROM sessions WHERE token_hash = %s",
                       (hashlib.sha256(token.encode()).hexdigest(),))
    return jsonify(status="signed out")


@app.errorhandler(psycopg.OperationalError)
def unavailable(error):
    return jsonify(error="Database unavailable. Please try again."), 503


DUMMY_HASH = generate_password_hash(secrets.token_urlsafe(32), method="scrypt")
initialize()
