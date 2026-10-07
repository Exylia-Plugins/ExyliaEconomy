# ExyliaEconomy

## Releasing

- Ordinary changes never touch `version`; it changes only during a release (`/exylia-release`), which bumps `build.gradle`, tags the commit `v<version>` and pushes both.
- The release workflow only runs on `v*` tags and expects the tag to match the `build.gradle` version; a push to `main` without a tag only builds.
