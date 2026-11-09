# iOS-inspired Liquid Glass Icon Pack for Android

Two original Android icon packs inspired by the color language of iOS and translucent Liquid Glass: **Light** and **Dark**. Each variant is a separate installable APK and can be selected from the icon/theme settings of a compatible Android launcher.

The artwork is original and uses generic pictograms; it is not an Apple product and does not include Apple's proprietary app artwork. The pack contains **42 designed icons**, with mappings for popular Google, Samsung, and third-party Android apps. App-filter mappings vary by app version and vendor activity name; the remaining icon assets are still available through the launcher's manual icon picker when supported.

## Install and apply

1. Download and install either `iOS-Liquid-Glass-Light.apk` or `iOS-Liquid-Glass-Dark.apk` from the latest successful GitHub Actions run.
2. Open the installed pack to preview its icons.
3. In your launcher settings, open **Appearance / Icons / Icon style** and choose the matching Liquid Glass pack.

The APK declares the Nova theme/icon-picker protocol, Lawnchair icon-pack/theme intents, and ADW, Apex, and Smart Launcher theme metadata. Support still depends on the installed launcher and its version. Pixel Launcher and many stock OEM launchers (including One UI Home on many versions) do not apply third-party APK icon packs directly; use a compatible launcher or its own icon customization feature.

## Build from source

Requires JDK 17, Android SDK Platform 35 / Build Tools 35.0.0, and Python 3.

```bash
python3 -m pip install -r tools/requirements.txt
python3 tools/generate_assets.py
python3 tools/validate_pack.py
./gradlew --no-daemon :app:assembleLightDebug :app:assembleDarkDebug
```

The APKs are generated at:

```text
app/build/outputs/apk/light/debug/app-light-debug.apk
app/build/outputs/apk/dark/debug/app-dark-debug.apk
```

GitHub Actions builds both APKs on pushes and pull requests to `main`, then uploads them as `liquid-glass-light-apk` and `liquid-glass-dark-apk` artifacts. The APKs are debug-signed and installable for personal use; a Play Store release would need a separate release signing process.

## Contents

- `app/src/main/res/xml/appfilter.xml` — launcher-compatible component-to-icon mappings.
- `app/src/light/` and `app/src/dark/` — independent image sets and theme labels for each APK.
- `tools/generate_assets.py` — reproducible source generator for the 42 icon illustrations and pack fallback overlays.

## Compatibility note

Icon packs are applied by the launcher, not by Android's system UI. A launcher must support the relevant icon-pack protocol to use these mappings. The pack does not need `QUERY_ALL_PACKAGES` and requests no runtime permissions.
