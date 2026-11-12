# Versioning

Keep `versionName` at `1.0.0` until this project is uploaded to GitHub. Do not automatically increase `versionName` or `versionCode`; wait for the user's explicit version-change request.

Keep the current `versionCode` at `2` so releases signed with the current key can replace each other without uninstalling. Use `build-release.ps1` to refresh the single current APK, source ZIP, and checksums in `dist`; do not keep superseded release copies.
