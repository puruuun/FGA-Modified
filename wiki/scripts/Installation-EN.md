# Installation

## Supported Environment

- Supports **Android 7 and later** on ARM devices (`arm64-v8a` / `armeabi-v7a`).
- **Testing is limited to the KR (Korean) and JP (Japanese) servers.** Behavior on other servers has not been verified.
- FGA(mod) reads the screen and taps via Android's Accessibility Service without requiring root access.

## Download and Updates

- **[Latest stable APK](https://github.com/puruuun/FGA-Modified/releases/latest)**: Download and install `FGA-mod-<version>-arm.apk` from GitHub Releases.
- **Obtainium**: Add `https://github.com/puruuun/FGA-Modified` as a GitHub source or import [obtainium.json](../../obtainium.json). Obtainium tracks stable releases only (keep `Include prereleases` off).
- **Development beta APK**: Listed with a `beta` tag in [all releases](https://github.com/puruuun/FGA-Modified/releases). Betas use a separate package (`io.github.fate_grand_automata.beta`, app name `FGA Beta`) and install alongside the stable build (`io.github.fate_grand_automata.cardcount`).

!!! warning "Install source"
    Do not install APKs downloaded from anywhere other than GitHub Releases and Obtainium.

### Coexistence and Updating

- The stable app is named **FGA(mod)** with package ID `io.github.fate_grand_automata.cardcount`.
- Because it uses a different package ID from the official FGA, **both apps can be installed side by side**.
- Existing FGA(mod) stable installations can be updated in place without uninstalling.
- To migrate scripts and support images from the official FGA, see [Import original FGA data](Original-FGA-Import-EN.md).

## Initial Setup and Permissions

After installing the app, configure the following items before running scripts:

1. **Accessibility Service**: Enable the FGA(mod) accessibility service from the app's prompt or Android system settings.
2. **Display over other apps (Overlay permission)**: Allow overlay permission so the floating play/pause control button can appear over the game.
3. **Storage folder**: Choose a folder to store support images and drop screenshots under **More options → Storage**.
4. **Battery optimization**: Tap **More options → Advanced → Disable battery optimization** and set FGA(mod) to unrestricted / not optimized so the OS does not stop the service in the background.

## License

The license notice can be viewed inside the app under **More options → Advanced**. Like the upstream FGA, this project is distributed under the MIT License.
