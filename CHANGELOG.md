# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-10-28

### Added

- Android implementation based on Credential Manager and Sign in with Google (`googleid`).
- `signIn`, `signInSilently`, `authorize`, `signOut` and `revokeAccess` methods.
- TypeScript definitions.
- `IN_PROGRESS` error when an interactive method is called while another one is running.
- `error.user` when `signIn()` with `scopes` signs the user in but the scopes are not granted.

### Known issues

- Android: `revokeAccess` is rejected by Google with `[28404]` (HTTP 400). Revoke refresh tokens from your backend instead.
