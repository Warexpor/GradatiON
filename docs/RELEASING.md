# Releasing GradatiON

An update installs over the old app only if all of these hold. `scripts/release.sh` checks them.

1. **Same signing key.** The release key is created once and never changes. Lose it and every user
   must uninstall (losing their chats) to update.
2. **Same package id**, `io.github.warexpor.gradation`.
3. **Higher versionCode.** It is derived from the version in `app/build.gradle.kts`
   (`major*10000 + minor*100 + patch`; 3.0.0 is 30000), so bumping the version is enough.
4. **Database and settings migrate.** The Room schema is at v4 with migrations in
   `DatabaseMigrations`. Change an entity, bump `version` and add a migration in the same commit.
   Never add `fallbackToDestructiveMigration`; it would wipe chats on update.
5. **R8 keeps what the framework inflates by name** (`ReleaseKeepRulesTest`).

## One time: the key

```bash
scripts/make-release-key.sh          # writes ~/.gradation-release/{gradation-release.jks,signing.properties}
echo <sha256 it prints> > docs/release-cert.sha256    # public, commit it
```

Back up `~/.gradation-release` (password manager and an offline copy). The password is in
`signing.properties`. The key is never committed. The committed fingerprint is what lets
`release.sh` refuse a build signed with the wrong key.

## Each release

1. Bump `appVersionMajor/Minor/Patch` in `app/build.gradle.kts`.
2. Move the `CHANGELOG.md` "Unreleased" notes under the new version, and add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Commit, merge to `main`.
4. `scripts/release.sh` runs the tests, builds the minified release, checks the signature against
   `docs/release-cert.sha256`, the package id and that versionCode grew, and leaves
   `build-out/gradation-<version>.apk` plus its `.sha256`.
5. Install it over the previous release on a real phone and open a chat from before. Then
   `git tag v<version> && git push origin v<version>` and attach the APK to a GitHub release.

Plain `./gradlew assembleRelease` fails on purpose without the signing properties, so an unsigned
or debug-signed release can't be produced by accident. Dev builds (`assembleDev`) are a separate app
(`.dev` suffix) signed with the public dev key and never update a release.

## CI (optional)

`.github/workflows/release.yml` does step 4 and publishes the release when a `v*` tag is pushed. It needs the
repo secrets `GRADATION_KEYSTORE_BASE64` (`base64 -w0 gradation-release.jks`),
`GRADATION_STORE_PASSWORD`, `GRADATION_KEY_ALIAS`, `GRADATION_KEY_PASSWORD`.
