# ExyliaEconomy

## Releasing

- Every finished change ships as a release: bump `version` in `build.gradle` (patch for fixes and small features, minor for larger ones) in the same commit as the change, then tag that commit `v<version>` and push both (`git push && git push origin v<version>`).
- The release workflow only runs on `v*` tags and expects the tag to match the `build.gradle` version; a push to `main` without a tag only builds.
