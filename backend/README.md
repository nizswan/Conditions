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

Start the Android Studio emulator (or connect one USB-debugging phone), then run:

    powershell -ExecutionPolicy Bypass -File .\connect-device.ps1

Run the Android app. It uses http://127.0.0.1:8000 through ADB reverse port
forwarding to reach your computer, without requesting local-network permission.
Run the script again after restarting the emulator or reconnecting the device.
The backend still listens only on localhost.

For an emulator, this script also checks Windows Location and starts a hidden
location-sync helper when a current fix is available. The helper forwards the
computer's coordinates every 10 seconds and exits when the emulator closes.
It runs locally without an account or API key. Enable Windows Settings > Privacy
& security > Location, including desktop app access, if Windows cannot provide a
position. Accuracy depends on the computer's location sources; it is not
guaranteed GPS accuracy. The status file is
`%LOCALAPPDATA%/Conditions/emulator-location-status.txt`.
No fixed Orlando/UCF coordinates are used. Each computer supplies its own fix.
USB-connected physical phones keep their own GPS location.

Stop without deleting accounts:

    docker compose down

Do not use "down -v" unless you intend to delete the database.

## Authentication

POST /signup: JSON with name, username, password.
POST /signin: JSON with username, password.
Both return a user and a random session token after success.
GET /me: send Authorization: Bearer <token> to verify the signed-in user.
Sessions stay valid until sign-out (or server-side revocation). Existing unexpired
sessions are kept when upgrading. Token hashes are stored in PostgreSQL.
Android encrypts the token with Android Keystore and excludes it from backups.
On launch, the saved account opens Library immediately, even if the backend is
offline. /me checks the token in the background; an explicit rejection returns to
sign-in. Older token-only installs need one successful /me request to cache the
account details. Restoring a session does not count as another login.
Sign Out removes the local token and account details and
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

Sign-up and sign-in open the account's libraries.
Android remembers valid sessions across restarts and rotation.
Library data and file references are stored locally on the Android device.

This is a local starter. Before deployment, configure HTTPS, a deployment secret
store, login rate limiting, recovery, and authorization on library/file endpoints.
Local HTTP is permitted only for 127.0.0.1 in debug builds.
