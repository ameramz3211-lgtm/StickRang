# StickRang: Sticker Maker (WAStickerApps)

Native Android app (Kotlin). Maker-first sticker app built to beat Sticker.ly: no ads, no watermark, no account.

## Screens
- **Create** (home tab): Cut-out, Text, GIF / Video, Photo shortcuts, each opening the maker in that mode, plus popular packs.
- **Explore:** 9 built-in packs (108 original stickers) with category chips (Funny, Love, Greetings, Party, Desi, Islamic) and search.
- **My Stickers:** packs you made or imported, with share and delete.

## Features
- **Cut-out:** one-tap background removal (Google ML Kit, on-device) with a white border.
- **Editor:** erase / restore brush, undo, two-finger move, zoom, rotate (`EditorActivity` + `EditorView`).
- **Animated:** GIF or video (3 s) to animated WebP, optionally with the background removed; Pulse, Bounce, Shake, Rainbow effects on any sticker. Own encoder in `AnimatedWebp.kt`, under WhatsApp's 500 KB limit.
- **Text:** English, Roman Urdu, Urdu script; round, square or text-only; 9 colours.
- **Share / import packs** as zip files, validated on import (size, 512x512, 3-30 stickers).
- One-tap "Add to WhatsApp" (WhatsApp and WhatsApp Business).

## Build
1. Open this folder in Android Studio (**File > Open**), let Gradle sync.
2. Put `stickrang-release.jks` and `keystore.properties` in this folder (see `KEYSTORE-INFO.txt` from the release kit).
3. **Build > Generate Signed App Bundle** or `./gradlew bundleRelease`. The AAB lands in `app/build/outputs/bundle/release/`.

## Adding sticker packs
Edit `PACKS` in `tools/make_packs.py` (id, name, tray emoji, category, stickers) and run `python3 tools/make_packs.py` (needs Pillow with raqm). Use original art only. Bump `image_data_version` when a pack changes.

## Store assets
`python3 tools/make_icons.py`, `tools/make_screenshots.py`, `tools/make_feature_graphic.py` regenerate the icon, screenshots and feature graphic in `store/`.
