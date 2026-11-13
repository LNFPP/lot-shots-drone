# Lot Shots — Drone (DJI RC Pro)

Floating lot picker over DJI Fly. Choose the lot before you shoot a sphere pano. When DJI Fly finishes stitching, the 360 is saved as
`Community_Lot042_2026-11-11_1432.jpg` and logged.

## Install on the RC Pro (easy way: GitHub builds it)

This project includes a GitHub Actions workflow. Every time the code is pushed to GitHub, it builds the app and posts it at:

`https://github.com/<you>/<repo>/releases/latest/download/LotShots.apk`

On the RC Pro, open that link in the built-in browser. When the download finishes, tap it and install.
The repo must be **public** so the controller can download without signing in to GitHub.
For updates, open the same link again. The app installs over the old version and keeps your communities and log.

## Install the other way (Android Studio on your Mac)

1. Install Android Studio. Choose **File → Open**, then select the `LotShotsDrone` folder and wait for Gradle sync.
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, then click **locate** to find `app-debug.apk`.
3. Copy the APK to a microSD card, put the card in the RC Pro, and tap the APK in the controller's file manager to install it.

## First-time setup on the controller

1. **Setup:** tap **Allow** on both rows (bubble over DJI Fly, photo access).
2. **+ Add community**: enter the name, then paste the lots. Use one per line, or ranges like `2-17`.
3. Shoot **one test sphere pano** in DJI Fly and let it finish stitching.
4. **Where DJI Fly saves 360s:** tap **Find my pano folder** and pick the folder it finds.
5. Tap a community box, then **+ New date**. This creates and selects today's folder.
6. **Start bubble**, then switch to DJI Fly.

## In flight

- Tap the blue **Lot __** bubble to open the number pad. Type the lot number and tap **✓ Set lot**.
  The bubble then shows that lot. Drag the bubble anywhere.
- The pad warns you if the lot already has a 360 or isn't in the community's list.
- When the stitch finishes, the bubble turns green and pops, the copy is named, and the bubble goes back to **Lot __**.
  Set the lot again before each 360.
- Wrong lot on the last 360? Tap **↺ Re-tag last 360** (it turns amber), type the right lot, then tap **↺ Re-tag to Lot …**. Tap Re-tag again to cancel.

## Where files go

- A renamed copy goes in `DCIM/LotShots/<Community>/<Date>/`. DJI Fly's original is never renamed or changed.
- Each date in a community has a 🗑 button that deletes that folder from the controller.
- Re-tag only renames the copy.
- **Export log CSV** saves the log to `Documents/LotShots/`.
