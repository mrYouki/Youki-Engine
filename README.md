# Youki Users

A standalone Android multi-user manager, split out of YoukiDex.

## Features
- List users (ID, "You" and "Active" badges)
- **Create a new user with a photo** (still image or GIF) — the photo is optional
- Change the photo of any existing user
- Switch between users and delete them (the primary user, ID 0, is protected)
- Works through **Shizuku** (primary) or **root** (fallback)
- Photos are shown inside the app; with **root** the app also sets the system user icon
- English and Arabic UI, light/dark, dynamic colors

## What was removed from YoukiDex
- App cloning into the new user (`pm install-existing` for the app and for Shizuku)
- Automatic permission granting (WRITE_SECURE_SETTINGS, notifications, media, ...)
- Enabling the accessibility service, notification listener and device admin
- Everything unrelated to users (dock, launcher, wallpapers, plugins, cast, ...)

Creating a user is now just `pm create-user` plus setting the photo.

## Build
Open the folder in Android Studio (JDK 17), or run:

    ./gradlew assembleDebug

Then install the APK, start Shizuku, and grant the app permission.

## Photos
- The photo is always saved inside the app and shown in the user list. This is all you get
  with **Shizuku**.
- With **root** (`su`), the app additionally sets the photo as the system user icon
  (Settings / lock screen) as a silent best-effort step, using a tiny helper
  (`RootIconRunner`) started through `su` + `app_process`.
- Shizuku is deliberately **not** used to change the system icon: the adb/shell user lacks
  `MANAGE_USERS` on many devices, which only produced long confusing errors.
- The app only shows a short "Photo saved" message. Any root failure is written to logcat
  (tag `YoukiUsers`), not shown on screen.
- A GIF animates inside the app; the system icon (root only) uses its first frame.
