# Conditions

Conditions is a Java Android app for organizing content around the conditions it was saved in and the conditions needed to open it. You put photos, audio, and documents into libraries, then use things like time, location, battery, Wi-Fi, Bluetooth, history, and weather to control when those libraries are available. The Web screen lets you explore the saved content through those same kinds of readings.

## Download, build, and run locally

These instructions use Windows PowerShell. You need Git (unless you download the ZIP), Docker Desktop running, and Android Studio with Android SDK 37 installed. The project's Gradle daemon configuration uses JDK 25. Docker runs the Flask login backend and PostgreSQL database; the Android app runs on an emulator or phone.

### 1. Get the project

```powershell
git clone https://github.com/nizswan/Conditions.git
cd Conditions
```

You can also use GitHub's **Code > Download ZIP**, extract it, and open PowerShell inside the extracted project folder.

### 2. Start the backend

From the project folder:

```powershell
cd backend
powershell -ExecutionPolicy Bypass -File .\setup.ps1
docker compose up --build -d
Invoke-RestMethod http://localhost:8000/health
```

The setup script creates the local database password in `backend/.env` if it does not already exist. Give Docker a moment to finish starting before checking health. To inspect startup problems, run `docker compose logs api db` from this folder.

### 3. Connect and run the Android app

Open the project folder in Android Studio and let Gradle sync. Start one emulator, or connect one Android phone with USB debugging enabled and authorize the computer. The app supports Android 7.0/API 24 and up.

Still in the `backend` folder, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\connect-device.ps1
cd ..
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

Open **Conditions** on the device and sign up. You can also use Android Studio's **Run** button to build, install, and launch the app. The APK from `assembleDebug` is at `app/build/outputs/apk/debug/app-debug.apk`.

The connection script forwards the device's `127.0.0.1:8000` to the backend on your computer using ADB. Run it again after restarting the emulator or reconnecting the phone. For an emulator, it also tries to sync your computer's Windows location; a physical phone uses its own location. See [the backend README](backend/README.md) for location setup and account details.

### 4. Stop or restart the backend

From the project folder:

```powershell
cd backend
docker compose down
```

To start it again, run `docker compose up -d`. After backend code changes, use `docker compose up --build -d`. Accounts stay in the Docker database volume when you stop it; `docker compose down -v` deletes that database.

Libraries, attachment references, history, and settings stay on the Android device. The backend handles accounts and sessions. Updating the installed app keeps local data; uninstalling it or clearing its storage removes that data. Weather needs internet access and a current location. Generated build files, local settings, and `backend/.env` are excluded from Git.

## Math we had to work through

A lot of the math came from trying to make different kinds of context work together. A time, a battery percentage, and a Wi-Fi name can all describe an item, but comparing them takes different rules.

### Modular arithmetic: time of day

Time of day wraps around. 11:55 PM and 12:05 AM are only 10 minutes apart, even though subtracting their minute-of-day values gives 1,430. For daily-time similarity, we use `delta = abs(a - b) % 1440`, then `min(delta, 1440 - delta)`. Dividing by 720 puts that circular distance between 0 and 1. Time conditions also handle ranges that cross midnight, like 10 PM to 2 AM.

### Discretized vs. continuous representations

We had to separate calendar date, daily time, and continuous time. Date groups timestamps into calendar days, daily time uses minutes on a repeating clock, and continuous time uses the full saved timestamp on a timeline. Two uploads can share a date or time of day without being the same moment. The timestamp still has finite millisecond precision; continuous here means it keeps moving forward instead of resetting each day. The choice changes which items overlap and which ones count as close.

### Representations without a natural total order

Wi-Fi names, Bluetooth device sets, and weather categories do not have a natural smaller-to-larger order. Alphabetically putting one network before another is useful for displaying labels, but it does not make the networks closer in meaning. We treat these as categories: Wi-Fi and weather use exact matches, and Bluetooth compares sets of device IDs. This is slightly different from being dimensionless: these are unordered labels, while normalized numeric values are unitless numbers.

### Dimension normalization and visualization

Battery percentages, temperatures, coordinates, and timestamps have very different scales. We normalize numeric plot values using `(value - min) / (max - min)` so each dimension fits into the display. The ranges come from the whole library, so selecting fewer items does not keep changing the scale. When every value is the same, we put it in the middle instead of dividing by zero.

The first two selected dimensions become horizontal and vertical position. The third becomes color, and the fourth becomes dot size. Categories get separate positions and colors with legends; their spacing or dot size is a display choice, not a real numerical distance between categories.

### Fixed seeding for randomness

Randomness is useful for sampling a crowded library and breaking ties, but we did not want the graph changing every time it redraws. Sampling and neighbor ties reuse a saved seed, and Reshuffle advances that seed. Category colors use a fixed seed of `42`, so the same category list gets the same color assignment. That keeps the display reproducible for the same data and state without removing the option to reshuffle.

### Metric builder and metric spaces

The metric builder lets us choose what "close" means by selecting factors and giving numeric factors weights. Numeric distances are normalized, the numeric weights add up to 1, and the result is a weighted sum of those distances. A constant factor contributes zero, and daily time uses the circular distance above.

The full comparison also ranks category matches first, then numeric distance. Any positive category weight enables that match; a larger category weight does not make it count more. So the complete neighbor rule is a similarity ranking, and the numeric part can give two different items distance zero when their selected readings match. Strictly speaking, that makes it a pseudometric on items rather than always a metric that distinguishes every item. Missing selected readings exclude an item from connections.

### Graph theory

In the Web, content items are vertices and neighbor connections are edges. Each item chooses up to `k` neighbors, and duplicate connections are combined into one undirected edge. An item can end up with more than `k` connections because other items can choose it too. Items with identical displayed readings can share a dot, so one visible dot can represent several vertices.

### A simple nearest-neighbor algorithm

This is the part that feels closest to machine learning. For each eligible displayed item, we compare it with the others, rank them by category matches and then weighted numeric distance, and connect the first `k`. Exact ties use the seeded shuffle. There is no trained model or prediction step here; it is a direct nearest-neighbor search using the definition of similarity we choose. The factors used for connections can differ from the dimensions shown on the plot, so a neighbor is not necessarily the closest dot on the screen.

## What you do on each screen

There are four main tabs, plus the sign-in/sign-up screen. The smaller editors, pickers, and content pages sit inside those flows.

### 1. Vault

See your libraries and live conditions. Search by title or switch between Active, Hidden, and All. Open an available library, or tap a locked one to see why it is locked. The library options let you change its title, description, conditions, or cover photo, or delete it.

**Library editor / Condition Creator:** Add a library with a title, optional description, and cover. Add condition pieces, configure their ranges, places, devices, networks, or weather, then combine their letters with `AND`, `OR`, `NOT`, and parentheses. For example, `(A OR B) AND NOT C`. The available condition types come from what you enable in Settings.

**Library contents / item viewer:** Search and sort items by newest, oldest, or name. Open an item and swipe between items, view saved context readings, preview photos and the first page of PDFs, play audio, or open a file in another app. Item options let you rename or remove it from that library.

### 2. Create

Give your payload a title and select up to 10 photos, audio files, or documents. The attachment picker lets you take a camera photo or choose multiple files, and you can remove attachments before saving.

**Library selection:** Continue to choose one or more unlocked libraries, or use `+` to create a library. Save to add the content along with a snapshot of the available enabled conditions at submission time. You can go back to adjust the payload before saving.

### 3. Settings

View your account and previous login information, edit your name or username, and enable or disable Time, Location, Battery, Bluetooth, Wi-Fi, History, and Environment. Android permission prompts appear where needed. You can also sign out, reset your local libraries, or delete your account; reset and deletion open confirmation dialogs.

### 4. Web

Choose an unlocked library and the starting dimensions, then continue to the figure. Explore saved readings through position, color, and dot size. These are the readings captured when the content was added.

**Figure controls:** Choose up to four dimensions, turn neighbor connections on or off, and change the number of neighbors. **Adjust similarity** opens the metric builder to add or remove factors and set weights. **Items to display** lets you select items and set a maximum count; Reshuffle changes the sample when more items are available than the limit.

**Opening points:** Tap a dot to open its content. When items overlap or are close together, a chooser lets you select the item. Items missing a plotted reading appear separately instead of being assigned a made-up value.

### 5. Sign in / Sign up

Create an account with a name, username, password, and password confirmation, or sign in to an existing account. A successful login opens Vault, and the app remembers the session across restarts.
