# cordova-plugin-authentication-google

Google Sign-In for [Apache Cordova](https://cordova.apache.org/) built on Google's current identity APIs:

| Platform | Implementation | Status |
|---|---|---|
| Android | [Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg) + `AuthorizationClient` | ✅ |
| iOS | [GoogleSignIn-iOS](https://github.com/google/GoogleSignIn-iOS) | 🚧 planned |
| Browser | [Google Identity Services](https://developers.google.com/identity/gsi/web) | 🚧 planned |

Unlike older plugins, it does not use the deprecated `GoogleSignInClient` API on Android.

## Requirements

- `cordova` >= 12
- `cordova-android` >= 13 (minSdk 24)

## Installation

```bash
cordova plugin add cordova-plugin-authentication-google --variable WEB_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
```

`WEB_CLIENT_ID` is the ID of the **Web application** OAuth client. If you don't have one yet, follow [Google Cloud setup](#google-cloud-setup) first.

To change the ID later, remove and add the plugin again. `cordova plugin rm` also asks for the variable: `cordova plugin rm cordova-plugin-authentication-google --variable WEB_CLIENT_ID=x`.

## Google Cloud setup

Sign in with Google is free: no billing account and no API needs to be enabled. You will create **two OAuth clients in the same project**:

| Client type | What it is for | Where it goes |
|---|---|---|
| **Web application** | Its ID is the audience (`aud`) of the ID tokens and is used by your backend to verify them. | `--variable WEB_CLIENT_ID=...` |
| **Android** | Lets Google recognise your app by package name + signing certificate. | Nowhere. Google matches it automatically. |

### 1. Create a project

Open [console.cloud.google.com](https://console.cloud.google.com/), click the project picker at the top left, then **New project**. Give it any name, click **Create**, and **select it** in the project picker when it is ready.

### 2. Configure Google Auth Platform (consent screen)

Go to **☰ → APIs & Services → OAuth consent screen**. This opens **Google Auth Platform**. Click **Get started** and fill in the wizard:

1. **App information:** the app name users will see, and a support email.
2. **Audience:** **External**. Choose *Internal* only if every user belongs to your Google Workspace organisation.
3. **Contact information:** your email.
4. Accept the policy and click **Create**.

> ⚠️ **The app name cannot contain "Google"** or other Google brands (Gmail, YouTube, Android, Gemini…). Otherwise creation fails with *"The request failed because the app name does not meet Google's requirements"*. Use something like `My App` or `Acme Auth Demo`. You can change it later in **Branding**.

Then open **Audience**. A new app is in **Testing** mode: under **Test users**, click **Add users** and add every Google account that will sign in while you develop. Only test users can sign in until you click **Publish app**.

### 3. Create the Web client

**Clients → Create client → Application type: Web application.** Any name works (e.g. `Web client`), and the JavaScript origins and redirect URIs can stay empty. Click **Create** and copy the client ID (`1234…-abc….apps.googleusercontent.com`). This is your `WEB_CLIENT_ID`.

### 4. Create the Android client

**Clients → Create client → Application type: Android**, with:

- **Package name:** the `id` attribute of the `<widget>` in your `config.xml`, for example `com.example.myapp`.
- **SHA-1 certificate fingerprint:** the SHA-1 of the certificate that signs the APK/AAB. Paste the **SHA-1**, not the SHA-256.

Get the SHA-1 with `keytool` (bundled with the JDK):

```bash
# Debug builds (cordova run / cordova build). On Windows: %USERPROFILE%\.android\debug.keystore
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
```

```bash
# Release builds signed with your own keystore
keytool -list -v -keystore my-release.keystore -alias my-alias
```

Create **one Android client per certificate**. A typical app needs three, all with the same package name:

1. the debug keystore of each developer machine;
2. your upload/release keystore;
3. if you publish on Google Play with **Play App Signing**, the *App signing key certificate* SHA-1 from Play Console → *Test and release → App integrity*. Google Play re-signs your app with that key, so installs from the store use it.

New clients can take from a few minutes to a few hours to work. Then [install the plugin](#installation) with the Web client ID.

### Troubleshooting

| Symptom | Cause and fix |
|---|---|
| *"The app name does not meet Google's requirements"* when creating the consent screen | The name contains "Google" or another Google brand. Rename it. |
| Account picker appears, then `CONFIGURATION_ERROR` with `[28444] Developer console is not set up correctly` | 1. `WEB_CLIENT_ID` is the **Android** client's ID. It must be the **Web application** client's ID; create one if you only have the Android client.<br>2. No Android client matches the package name + SHA-1 of the build you are running (typo, SHA-256 pasted instead of SHA-1, debug vs release vs Play signing key).<br>3. The Web and Android clients are in different projects. Their IDs must start with the same project number.<br>4. The client was created moments ago; wait and retry. |
| `CONFIGURATION_ERROR` with *WEB_CLIENT_ID is not configured* | The plugin was installed without `--variable WEB_CLIENT_ID=...`. |
| An account cannot sign in or gets *Access blocked* | The app is in **Testing** mode and that account is not a test user. Add it under **Audience → Test users**. |
| *"Google hasn't verified this app"* when calling `authorize` with sensitive scopes (Drive, Calendar…) | Expected while testing: click *Continue*. Production apps that request sensitive scopes must go through verification in **Verification center**. |

For errors returned by the methods, see [Errors](#errors).

## Usage

The plugin is available as `cordova.plugins.GoogleAuth` after `deviceready`.

```js
document.addEventListener('deviceready', () => {
    const GoogleAuth = cordova.plugins.GoogleAuth;

    document.getElementById('google-sign-in').addEventListener('click', async () => {
        try {
            // 1. Your backend creates a single-use nonce and remembers it.
            const { nonce } = await fetch('https://api.example.com/auth/google/nonce').then((r) => r.json());

            // 2. Google embeds the nonce in the ID token.
            const user = await GoogleAuth.signIn({ mode: 'button', nonce });

            // 3. Your backend verifies the ID token and that its nonce matches (see Security).
            const session = await fetch('https://api.example.com/auth/google', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ idToken: user.idToken })
            }).then((r) => r.json());

            console.log('Signed in as', user.email, session);
        } catch (error) {
            if (error.code !== GoogleAuth.ErrorCode.CANCELLED) {
                console.error(error.code, error.message);
            }
        }
    });
});
```

The nonce protects against replay attacks: a stolen ID token cannot be reused because its nonce was already consumed. It only works if your **backend** generates and checks it; a nonce generated in the app and never verified adds no protection. If you have no backend, omit it.

### `signIn(options?)` → `Promise<SignInResult>`

Shows the Google account picker.

| Option | Type | Description |
|---|---|---|
| `mode` | `'bottomSheet' \| 'button'` | `bottomSheet` (default) shows the Credential Manager sheet. Use `button` when the user taps a "Sign in with Google" button; it shows the full account chooser. |
| `nonce` | `string` | Embedded in the ID token to prevent replay attacks. Recommended. |
| `hostedDomain` | `string` | Restrict to a Google Workspace domain. |
| `filterByAuthorizedAccounts` | `boolean` | Only offer accounts that already used the app. When omitted, those accounts are tried first and then all accounts on the device. |
| `autoSelect` | `boolean` | Sign in without a tap when only one account already signed in to this app. Disabled again after `signOut()` until the user signs in manually. |
| `scopes` | `string[]` | Extra OAuth scopes. The result then also includes `accessToken` and `grantedScopes`. |
| `offlineAccess` | `boolean` | Also return a `serverAuthCode` (requires `scopes`). |
| `webClientId` | `string` | Overrides the installed `WEB_CLIENT_ID`. |

Result:

```ts
{
  id: string;          // stable account id ("sub" claim)
  email: string;
  displayName?: string;
  givenName?: string;
  familyName?: string;
  photoUrl?: string;
  idToken: string;
  accessToken?: string;     // only with scopes
  serverAuthCode?: string;  // only with offlineAccess
  grantedScopes?: string[]; // only with scopes
}
```

With `scopes`, sign-in and authorization happen in one call. If the user signs in but the scopes are not granted (for example, they cancel the consent screen), the promise rejects and `error.user` contains the signed-in user, so you can continue without the extra scopes:

```js
try {
    const result = await GoogleAuth.signIn({ scopes: ['https://www.googleapis.com/auth/drive.file'] });
} catch (error) {
    if (error.user) {
        // Signed in, but without Drive access.
    }
}
```

### `signInSilently(options?)` → `Promise<GoogleUser>`

Restores the session of the last account that signed in on this device. It never asks the user to pick an account; at most, Android briefly shows a "Signing in" sheet with the account.

It rejects with `NO_CREDENTIAL`, without showing anything, when the user never signed in on this device, signed out with `signOut()`, or the app data was cleared. That is the cue to show your sign-in button.

### `authorize({ scopes, offlineAccess?, forceRefreshToken?, email? })` → `Promise<AuthorizationResult>`

Requests OAuth scopes for the signed-in account (for example to call Google Drive or Calendar APIs). Shows a consent screen the first time.

```js
const { accessToken, serverAuthCode } = await GoogleAuth.authorize({
    scopes: ['https://www.googleapis.com/auth/drive.file'],
    offlineAccess: true
});
```

### `signOut()` → `Promise<void>`

Clears the local session: the next `signIn()` shows the picker again, auto-select is disabled, and `signInSilently()` rejects with `NO_CREDENTIAL`.

### `revokeAccess({ email?, scopes? })` → `Promise<void>`

Revokes the access the user granted to your app and signs out. Call it while the user is still signed in: after `signOut()` it rejects with `NOT_SIGNED_IN`.

> **Known limitation (Android):** in our tests on Android 12 and 16, Google rejects the on-device revocation with `[28404]` (HTTP 400), even for consented scopes such as `drive.file`. Access tokens issued on the device cannot be revoked through `https://oauth2.googleapis.com/revoke` either ("Token is not revocable"). The method then rejects with a message explaining it. If you must guarantee revocation (for example when deleting an account), exchange the `serverAuthCode` on your backend and revoke the **refresh token** there. Otherwise, the user can remove the app at [myaccount.google.com/connections](https://myaccount.google.com/connections).

### Errors

Every method rejects with a `GoogleAuthError` that has a stable `code`:

| Code | Meaning |
|---|---|
| `CANCELLED` | The user closed the picker or consent screen. |
| `NO_CREDENTIAL` | No eligible Google account. For `signInSilently()`: no session to restore (never signed in on this device, or signed out). |
| `NOT_SIGNED_IN` | The operation needs a previously signed-in account. |
| `CONFIGURATION_ERROR` | Wrong or missing client ID, SHA-1 or package name. |
| `NETWORK_ERROR` | No connection to Google. |
| `INVALID_ARGUMENT` | Invalid options. |
| `INTERRUPTED` | The operation was interrupted; it can be retried. |
| `IN_PROGRESS` | `signIn`, `signInSilently`, `authorize` or `revokeAccess` was called while another of them was still running (for example, a double tap on the sign-in button). |
| `NOT_SUPPORTED` | Not available on this device or platform. |
| `UNKNOWN` | Anything else; see `message` and `nativeError`. |

## Security

- **Always verify the ID token on your server** before trusting it: check the signature, that `aud` equals your `WEB_CLIENT_ID`, that `iss` is `accounts.google.com` or `https://accounts.google.com`, and that it has not expired. Use the [official Google libraries](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token).
- If you pass a `nonce`, check that the token's `nonce` claim equals one your server issued and has not used yet, then delete it.
- Identify users by `id` (the `sub` claim), not by `email`: the email of a Google account can change.
- Never ship an OAuth client secret in the app. Exchange `serverAuthCode` for tokens on your backend.

Example backend check with Node.js and [`google-auth-library`](https://www.npmjs.com/package/google-auth-library):

```js
const { OAuth2Client } = require('google-auth-library');
const client = new OAuth2Client();

async function verifyGoogleIdToken (idToken, expectedNonce) {
    const ticket = await client.verifyIdToken({ idToken, audience: process.env.WEB_CLIENT_ID });
    const payload = ticket.getPayload(); // signature, aud, iss and exp are already checked
    if (payload.nonce !== expectedNonce) {
        throw new Error('Invalid nonce');
    }
    return { googleId: payload.sub, email: payload.email };
}
```

## Example app

[example/](example/) contains a test app with a button for every method. See [example/README.md](example/README.md).

## Advanced: library versions

The native dependencies can be overridden at install time:

```bash
cordova plugin add cordova-plugin-authentication-google --variable WEB_CLIENT_ID=... --variable ANDROIDX_CREDENTIALS_VERSION=1.6.0 --variable GOOGLE_ID_VERSION=1.2.1 --variable PLAY_SERVICES_AUTH_VERSION=22.0.0
```

## License

[MIT](LICENSE)
