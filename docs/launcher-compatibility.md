# Launcher format and compatibility notes

This project packages icons as Android drawable resources and provides a launcher mapping file at `app/src/main/res/xml/appfilter.xml`. An appfilter entry uses the component form `ComponentInfo{package/activity}` and names the icon drawable. The consuming launcher must understand the icon-pack metadata and protocol; installing a pack alone does not replace the system or OEM launcher icons.

The manifest advertises observed protocols for Nova's theme/icon picker, current and legacy Lawnchair, ADW, Apex, and Smart Launcher. These intent actions and category values are cross-checked against the Lawnicons manifest. Individual launcher releases may change which theme activity or metadata they accept, so this is a compatibility target rather than a universal guarantee. Many stock Android/OEM launchers do not apply third-party APK packs.

The two product flavors contain the same 42 icon names and mapping catalog, with separate Light and Dark drawable PNG sets. When a package/activity mapping does not match an app installed on a particular ROM or version, a launcher that supports manual icon selection can still use the drawable by name.

## Sources

- [Lawnicons AndroidManifest.xml](https://raw.githubusercontent.com/LawnchairLauncher/lawnicons/develop/app/src/main/AndroidManifest.xml) — active Nova, Lawnchair, ADW, Apex, and Smart Launcher intent filters.
- [Lawnicons repository](https://github.com/LawnchairLauncher/lawnicons) — open-source Android icon pack example and launcher application.
- [Numix: Why an Android Icon Pack Only Works on Some Launchers](https://numixproject.org/android/icon-pack-not-working-android-launcher/) — component mapping shape and launcher-specific support caveats.
- [Android Developers: Create app icons](https://developer.android.com/studio/write/create-app-icons) — native Android launcher icon resources and density guidance.
