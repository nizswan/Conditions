# Conditions local backend

One Python file, app.py, handles authentication. Android calls it through Auth.java.
PostgreSQL stores users and sessions. Docker runs both services.

## Start

Open Docker Desktop and wait for the engine to start. In PowerShell:

    cd Z:\Conditions\backend
    powershell -ExecutionPolicy Bypass -File .\setup.ps1
    docker compose up --build -d

Check:

    Invoke-RestMethod http://localhost:8000/health
    docker compose logs api

Then run the Android app on the Android Studio emulator. The app uses
http://10.0.2.2:8000 to reach your computer. A physical phone requires different
network configuration; this setup intentionally listens only on localhost.

Stop without deleting accounts:

    docker compose down

Do not use "down -v" unless you intend to delete the database.

## Authentication

POST /signup: JSON with name, username, password.
POST /signin: JSON with username, password.
Both return a user and a random session token after success.
GET /me: send Authorization: Bearer <token> to verify the signed-in user.
New sessions expire after 30 days. Token hashes are stored in PostgreSQL.
Android encrypts the token with Android Keystore and excludes it from backups.
On launch, /me validates the saved token before opening Library. Restoring a
session does not count as another login. Sign Out removes the local token and
revokes it on the server when the server is reachable.
The backend records previous successful login timestamps. New users have no
previous login; historical logins before this feature was added are unknown.

Usernames contain 1–32 ASCII letters/numbers and are case-insensitively unique.
Passwords contain 8–128 characters, at least one ASCII letter and one number.
Special characters are allowed. Names contain 1–100 characters after trimming.
Passwords are stored as salted scrypt hashes, never as readable passwords.
Queries use parameters, and the database enforces username uniqueness.

The generated PostgreSQL service password lives in the ignored .env file.
It is separate from app users' passwords. Do not commit or share .env.
PostgreSQL is not exposed to the host; only the API can connect to it.

## Current scope

Sign-up and sign-in open LibraryFragment with a welcome and an empty-library message.
Android remembers valid sessions across restarts and rotation.
Forgot Password is still a placeholder: accounts have no recovery email.
Library persistence and file upload/storage will be added later.

This is a local starter. Before deployment, configure HTTPS, a deployment secret
store, login rate limiting, recovery, and authorization on library/file endpoints.
Local HTTP is permitted only for the emulator address in debug builds.

## Verify

With the containers running:

    python tests/smoke.py

The test creates disposable users with randomly generated usernames. Their names
start with "Backend test". It checks signup, duplicate usernames, validation,
password verification, and session authorization.
