# Conditions
Conditions is an Android app for organizing and exploring personal content, with secure user authentication powered by a Python and PostgreSQL backend.

## Project structure

- `app/`: Android application written in Java.
- `backend/`: Flask authentication API and PostgreSQL configuration.
- `gradle/`, `gradlew`, and `gradlew.bat`: Gradle wrapper and build configuration.

## Getting started

1. Clone this repository:

   ```sh
   git clone https://github.com/nizswan/Conditions.git
   ```

2. Open the `Conditions` folder in Android Studio. Install Android SDK 37 and allow Gradle to sync. The Gradle daemon is configured to use JDK 25.
3. Start Docker Desktop. In PowerShell, from the project folder:

   ```powershell
   cd backend
   powershell -ExecutionPolicy Bypass -File .\setup.ps1
   docker compose up --build -d
   Invoke-RestMethod http://localhost:8000/health
   ```

4. Run the Android app on an emulator. Debug builds connect to the local backend through `http://10.0.2.2:8000`.

See [backend/README.md](backend/README.md) for authentication details, verification, and shutdown instructions. Physical devices require additional network configuration.

Each developer generates their own ignored `backend/.env` file using `setup.ps1`. Database accounts and sessions stay in that developer's local Docker volume. Do not commit passwords, local configuration, or database contents.

## Current scope

The app supports sign-up, sign-in, saved sessions, and sign-out. Library persistence, file storage, and password recovery are planned. The backend is intended for local development; see its README for deployment requirements.

## Collaboration

Pull the latest changes before starting work, create a branch for your changes, and open a pull request for review. Generated build files and local IDE settings are excluded from version control.
