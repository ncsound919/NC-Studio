# Security policy — NC Studio

## Reporting

Report vulnerabilities privately to the repository owner. Never include a signing
key or a project file containing unreleased audio.

## What the app handles

- Audio recordings, project state and `.ncbeat` files.
- A foreground media-playback service.
- Local analytics counters and a narrow deep-link entry.

## Controls in place

- `android:allowBackup="false"` and `android:usesCleartextTraffic="false"` in the
  manifest.
- Recordings and projects are written through the app's own storage and exposed
  to other apps only through the FileProvider paths declared in
  `res/xml/file_paths.xml`.
- The remote-session activity is `exported="false"`.
- Deep links accept only `ncstudio://open`; the path is used for an analytics
  label only and never as a filesystem path or project id.
- Analytics are in-memory and contain no audio, file path or device identifier.

## Known gaps

- No release signing config; do not distribute a build until an upload key is
  configured and the APK is verified.
- No certificate pinning (the app does not call a remote API by default).
- No device test of file import from a third-party share sheet.
- The foreground service and audio route changes have not been exercised on
  hardware.

## Secrets

No keystore password, API key or signing secret belongs in this repository.
