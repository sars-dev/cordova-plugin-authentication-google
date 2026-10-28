// Type definitions for cordova-plugin-authentication-google

export type GoogleAuthErrorCode =
    | 'CANCELLED'
    | 'NO_CREDENTIAL'
    | 'NOT_SIGNED_IN'
    | 'CONFIGURATION_ERROR'
    | 'NETWORK_ERROR'
    | 'INVALID_ARGUMENT'
    | 'INTERRUPTED'
    | 'IN_PROGRESS'
    | 'NOT_SUPPORTED'
    | 'UNKNOWN';

export interface GoogleAuthError extends Error {
    name: 'GoogleAuthError';
    code: GoogleAuthErrorCode;
    /** Raw platform error, when available. Useful for debugging only. */
    nativeError?: unknown;
    /**
     * Only set by `signIn()` with `scopes`: the user signed in successfully, but the requested scopes
     * were not granted (for example, the consent screen was cancelled).
     */
    user?: GoogleUser;
}

export interface GoogleUser {
    /** Stable Google account identifier (the `sub` claim of the ID token). */
    id: string;
    email: string;
    displayName?: string;
    givenName?: string;
    familyName?: string;
    photoUrl?: string;
    /** JWT signed by Google. Always verify it on your backend before trusting it. */
    idToken: string;
}

export interface AuthorizationResult {
    accessToken: string;
    /** Present when `offlineAccess` was requested. Exchange it on your backend for a refresh token. */
    serverAuthCode?: string;
    grantedScopes: string[];
}

export type SignInResult = GoogleUser & Partial<AuthorizationResult>;

export interface BaseOptions {
    /** Overrides the WEB_CLIENT_ID configured at install time. */
    webClientId?: string;
}

export interface SignInOptions extends BaseOptions {
    /**
     * `bottomSheet` (default) shows the Credential Manager sheet.
     * `button` shows the full account chooser; use it when the user taps a "Sign in with Google" button.
     */
    mode?: 'bottomSheet' | 'button';
    /** Random value embedded in the ID token, used to prevent replay attacks. */
    nonce?: string;
    /** Restrict sign-in to a Google Workspace domain. */
    hostedDomain?: string;
    /**
     * Only offer accounts that already signed in to this app.
     * When omitted, authorized accounts are tried first and then every account on the device.
     */
    filterByAuthorizedAccounts?: boolean;
    /**
     * Sign in without a tap when only one account already signed in to this app.
     * Disabled again after `signOut()` until the user signs in manually.
     */
    autoSelect?: boolean;
    /** Additional OAuth scopes. When set, the result also contains an access token. */
    scopes?: string[];
    /** Also return a server auth code. Requires `scopes`. */
    offlineAccess?: boolean;
    /** Force a new refresh token when exchanging the server auth code. */
    forceRefreshToken?: boolean;
}

export interface SignInSilentlyOptions extends BaseOptions {
    nonce?: string;
    hostedDomain?: string;
}

export interface AuthorizeOptions extends BaseOptions {
    scopes: string[];
    offlineAccess?: boolean;
    forceRefreshToken?: boolean;
    /** Account to authorize. Defaults to the last signed-in account. */
    email?: string;
}

export interface RevokeAccessOptions {
    /** Account to revoke. Defaults to the last signed-in account. */
    email?: string;
    /** Scopes to revoke. Defaults to `openid`, `email` and `profile`. */
    scopes?: string[];
}

export interface GoogleAuthPlugin {
    readonly ErrorCode: { readonly [K in GoogleAuthErrorCode]: K };
    readonly GoogleAuthError: new (code: GoogleAuthErrorCode, message?: string) => GoogleAuthError;

    signIn(options?: SignInOptions): Promise<SignInResult>;
    signInSilently(options?: SignInSilentlyOptions): Promise<GoogleUser>;
    authorize(options: AuthorizeOptions): Promise<AuthorizationResult>;
    signOut(): Promise<void>;
    revokeAccess(options?: RevokeAccessOptions): Promise<void>;
}

// The plugin is exposed by Cordova as `cordova.plugins.GoogleAuth` after `deviceready`.
declare global {
    interface CordovaPlugins {
        GoogleAuth: GoogleAuthPlugin;
    }
}
