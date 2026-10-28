package cordova.plugin.googleauth;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CredentialOption;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialInterruptedException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.GetCredentialUnsupportedException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Google Sign-In for Cordova on Android.
 *
 * Authentication (who the user is) uses Credential Manager with Sign in with Google.
 * Authorization (OAuth scopes, access tokens, server auth codes) uses the Identity AuthorizationClient.
 */
public class GoogleAuthPlugin extends CordovaPlugin {

    private static final String TAG = "GoogleAuth";

    private static final String CONFIG_WEB_CLIENT_ID = "GoogleAuthWebClientId";
    private static final String STORE_NAME = "cordova_plugin_authentication_google";
    private static final String STORE_EMAIL = "email";
    private static final String STATE_PENDING_USER = "pendingUser";
    private static final String ACCOUNT_TYPE = "com.google";

    private static final int REQUEST_AUTHORIZE = 0x6A01;

    private static final List<String> BASIC_SCOPES = Arrays.asList("openid", "email", "profile");

    // Must exist in ErrorCode (www/google-auth.js) and GoogleAuthErrorCode (types/index.d.ts).
    // IN_PROGRESS is only raised by the JavaScript layer.
    private static final String CANCELLED = "CANCELLED";
    private static final String NO_CREDENTIAL = "NO_CREDENTIAL";
    private static final String NOT_SIGNED_IN = "NOT_SIGNED_IN";
    private static final String CONFIGURATION_ERROR = "CONFIGURATION_ERROR";
    private static final String NETWORK_ERROR = "NETWORK_ERROR";
    private static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    private static final String INTERRUPTED = "INTERRUPTED";
    private static final String NOT_SUPPORTED = "NOT_SUPPORTED";
    private static final String UNKNOWN = "UNKNOWN";

    private CredentialManager credentialManager;
    private Executor mainExecutor;

    // Set while the consent screen opened by authorize() is visible; cleared when its result arrives.
    private CallbackContext pendingAuthorization;
    private JSONObject pendingUser;

    private String lastAccessToken;

    @Override
    protected void pluginInitialize() {
        Context context = cordova.getContext();
        credentialManager = CredentialManager.create(context);
        mainExecutor = ContextCompat.getMainExecutor(context);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        JSONObject options = args.optJSONObject(0);
        if (options == null) {
            options = new JSONObject();
        }

        switch (action) {
            case "signIn":
                signIn(options, callbackContext);
                return true;
            case "signInSilently":
                signInSilently(options, callbackContext);
                return true;
            case "authorize":
                authorize(options, null, callbackContext);
                return true;
            case "signOut":
                signOut(callbackContext);
                return true;
            case "revokeAccess":
                revokeAccess(options, callbackContext);
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Authentication
    // ---------------------------------------------------------------------------------------------

    private void signIn(JSONObject options, CallbackContext callbackContext) {
        String clientId = webClientId(options, callbackContext);
        if (clientId == null) {
            return;
        }

        String nonce = optString(options, "nonce");
        String hostedDomain = optString(options, "hostedDomain");

        if ("button".equals(optString(options, "mode"))) {
            GetSignInWithGoogleOption.Builder builder = new GetSignInWithGoogleOption.Builder(clientId);
            if (nonce != null) {
                builder.setNonce(nonce);
            }
            if (hostedDomain != null) {
                builder.setHostedDomainFilter(hostedDomain);
            }
            requestCredential(builder.build(), false, options, callbackContext, null);
            return;
        }

        boolean autoSelect = options.optBoolean("autoSelect", false);
        if (options.has("filterByAuthorizedAccounts")) {
            boolean filter = options.optBoolean("filterByAuthorizedAccounts");
            requestCredential(googleIdOption(clientId, filter, autoSelect, nonce, hostedDomain),
                    false, options, callbackContext, null);
            return;
        }

        // Flow recommended by Google: first offer only accounts that already signed in to this app;
        // if there are none, offer every Google account on the device.
        CredentialOption allAccounts = googleIdOption(clientId, false, autoSelect, nonce, hostedDomain);
        requestCredential(googleIdOption(clientId, true, autoSelect, nonce, hostedDomain),
                false, options, callbackContext, allAccounts);
    }

    private void signInSilently(JSONObject options, CallbackContext callbackContext) {
        // Without a previous sign-in (or after signOut()) Credential Manager cannot auto-select an account
        // and would show the account picker, which a silent sign-in must never do.
        if (storedEmail() == null) {
            fail(callbackContext, NO_CREDENTIAL, "No previous sign-in to restore", null);
            return;
        }
        String clientId = webClientId(options, callbackContext);
        if (clientId == null) {
            return;
        }
        CredentialOption option = googleIdOption(clientId, true, true,
                optString(options, "nonce"), optString(options, "hostedDomain"));
        // Empty options: a silent sign-in never requests scopes, so it never shows a consent screen.
        requestCredential(option, true, new JSONObject(), callbackContext, null);
    }

    private static CredentialOption googleIdOption(String clientId, boolean filterByAuthorizedAccounts,
                                                   boolean autoSelect, String nonce, String hostedDomain) {
        GetGoogleIdOption.Builder builder = new GetGoogleIdOption.Builder()
                .setServerClientId(clientId)
                .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
                .setAutoSelectEnabled(autoSelect);
        if (nonce != null) {
            builder.setNonce(nonce);
        }
        if (hostedDomain != null) {
            builder.setHostedDomainFilter(hostedDomain);
        }
        return builder.build();
    }

    /**
     * @param fallback option retried once if {@code option} fails with NoCredentialException; null to not retry.
     */
    private void requestCredential(CredentialOption option, boolean silent, JSONObject options,
                                   CallbackContext callbackContext, CredentialOption fallback) {
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .setPreferImmediatelyAvailableCredentials(silent)
                .build();

        credentialManager.getCredentialAsync(cordova.getActivity(), request, null, mainExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        handleCredential(response.getCredential(), options, callbackContext);
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (fallback != null && e instanceof NoCredentialException) {
                            requestCredential(fallback, silent, options, callbackContext, null);
                            return;
                        }
                        Log.w(TAG, "getCredential failed: " + e.getType() + " " + e.getMessage());
                        fail(callbackContext, credentialErrorCode(e), e.getMessage(), e.getType());
                    }
                });
    }

    private void handleCredential(Credential credential, JSONObject options, CallbackContext callbackContext) {
        if (!(credential instanceof CustomCredential) || !isGoogleIdCredential(credential.getType())) {
            fail(callbackContext, UNKNOWN, "Unexpected credential type: " + credential.getType(), null);
            return;
        }

        JSONObject user;
        try {
            GoogleIdTokenCredential googleCredential = GoogleIdTokenCredential.createFrom(credential.getData());
            user = userToJson(googleCredential);
        } catch (Exception e) {
            // createFrom() is Kotlin and throws GoogleIdTokenParsingException without declaring it.
            fail(callbackContext, UNKNOWN, "Could not read the Google ID token: " + e.getMessage(), null);
            return;
        }

        rememberEmail(user.optString("email", null));

        JSONArray scopes = options.optJSONArray("scopes");
        if (scopes != null && scopes.length() > 0) {
            authorize(options, user, callbackContext);
        } else {
            callbackContext.success(user);
        }
    }

    private static boolean isGoogleIdCredential(String type) {
        return GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(type)
                || GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL.equals(type);
    }

    private static JSONObject userToJson(GoogleIdTokenCredential credential) throws JSONException {
        String idToken = credential.getIdToken();
        JSONObject claims = decodeJwtPayload(idToken);

        JSONObject user = new JSONObject();
        user.put("id", claims.optString("sub", credential.getId()));
        user.put("email", claims.optString("email", credential.getId()));
        putIfNotNull(user, "displayName", credential.getDisplayName());
        putIfNotNull(user, "givenName", credential.getGivenName());
        putIfNotNull(user, "familyName", credential.getFamilyName());
        if (credential.getProfilePictureUri() != null) {
            user.put("photoUrl", credential.getProfilePictureUri().toString());
        }
        user.put("idToken", idToken);
        return user;
    }

    /** Reads the claims of a JWT without verifying it; verification must happen on the backend. */
    private static JSONObject decodeJwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return new JSONObject();
            }
            byte[] json = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(json, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | JSONException e) {
            return new JSONObject();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Authorization
    // ---------------------------------------------------------------------------------------------

    /**
     * @param user user from signIn() that receives the token fields; null when called from authorize().
     */
    private void authorize(JSONObject options, JSONObject user, CallbackContext callbackContext) {
        List<Scope> scopes = toScopes(options.optJSONArray("scopes"));
        if (scopes.isEmpty()) {
            fail(callbackContext, INVALID_ARGUMENT, "At least one scope is required", null, user);
            return;
        }

        AuthorizationRequest.Builder builder = AuthorizationRequest.builder().setRequestedScopes(scopes);

        if (options.optBoolean("offlineAccess", false)) {
            String clientId = webClientId(options, callbackContext);
            if (clientId == null) {
                return;
            }
            builder.requestOfflineAccess(clientId, options.optBoolean("forceRefreshToken", false));
        }

        String email = user != null ? user.optString("email", null) : optString(options, "email");
        if (email == null) {
            email = storedEmail();
        }
        if (email != null) {
            builder.setAccount(new Account(email, ACCOUNT_TYPE));
        }

        authorizationClient().authorize(builder.build())
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        launchConsent(result.getPendingIntent(), user, callbackContext);
                    } else {
                        deliverAuthorization(result, user, callbackContext);
                    }
                })
                .addOnFailureListener(e -> failApi(callbackContext, e, user));
    }

    private void launchConsent(PendingIntent pendingIntent, JSONObject user, CallbackContext callbackContext) {
        if (pendingAuthorization != null) {
            fail(pendingAuthorization, INTERRUPTED, "Superseded by a new authorization request", null, pendingUser);
        }
        pendingAuthorization = callbackContext;
        pendingUser = user;

        cordova.setActivityResultCallback(this);
        try {
            cordova.getActivity().startIntentSenderForResult(
                    pendingIntent.getIntentSender(), REQUEST_AUTHORIZE, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException e) {
            pendingAuthorization = null;
            pendingUser = null;
            fail(callbackContext, UNKNOWN, "Could not open the consent screen: " + e.getMessage(), null, user);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode != REQUEST_AUTHORIZE || pendingAuthorization == null) {
            return;
        }
        CallbackContext callbackContext = pendingAuthorization;
        JSONObject user = pendingUser;
        pendingAuthorization = null;
        pendingUser = null;

        if (resultCode == Activity.RESULT_CANCELED && intent == null) {
            fail(callbackContext, CANCELLED, "The user cancelled the authorization", null, user);
            return;
        }
        try {
            AuthorizationResult result = authorizationClient().getAuthorizationResultFromIntent(intent);
            deliverAuthorization(result, user, callbackContext);
        } catch (ApiException e) {
            failApi(callbackContext, e, user);
        }
    }

    @Override
    public Bundle onSaveInstanceState() {
        Bundle state = new Bundle();
        if (pendingUser != null) {
            state.putString(STATE_PENDING_USER, pendingUser.toString());
        }
        return state;
    }

    @Override
    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        // Android destroyed the activity while the consent screen was open. Cordova recreated it and
        // provides a new callback; the result then arrives in onActivityResult().
        pendingAuthorization = callbackContext;
        pendingUser = null;
        String user = state != null ? state.getString(STATE_PENDING_USER) : null;
        if (user != null) {
            try {
                pendingUser = new JSONObject(user);
            } catch (JSONException ignored) {
                // The saved user is unreadable: return only the authorization fields.
            }
        }
    }

    private void deliverAuthorization(AuthorizationResult result, JSONObject user, CallbackContext callbackContext) {
        try {
            JSONObject out = user != null ? user : new JSONObject();
            out.put("accessToken", result.getAccessToken());
            putIfNotNull(out, "serverAuthCode", result.getServerAuthCode());
            out.put("grantedScopes", new JSONArray(result.getGrantedScopes()));
            lastAccessToken = result.getAccessToken();
            callbackContext.success(out);
        } catch (JSONException e) {
            fail(callbackContext, UNKNOWN, e.getMessage(), null);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Sign out / revoke
    // ---------------------------------------------------------------------------------------------

    private void signOut(CallbackContext callbackContext) {
        if (lastAccessToken != null) {
            // Best effort: drop the cached token so the next authorize() fetches a fresh one.
            authorizationClient().clearToken(ClearTokenRequest.builder().setToken(lastAccessToken).build());
            lastAccessToken = null;
        }
        rememberEmail(null);

        credentialManager.clearCredentialStateAsync(new ClearCredentialStateRequest(), null, mainExecutor,
                new CredentialManagerCallback<Void, ClearCredentialException>() {
                    @Override
                    public void onResult(Void unused) {
                        callbackContext.success();
                    }

                    @Override
                    public void onError(ClearCredentialException e) {
                        fail(callbackContext, UNKNOWN, e.getMessage(), e.getType());
                    }
                });
    }

    private void revokeAccess(JSONObject options, CallbackContext callbackContext) {
        String email = optString(options, "email");
        if (email == null) {
            email = storedEmail();
        }
        if (email == null) {
            fail(callbackContext, NOT_SIGNED_IN, "No signed-in account to revoke", null);
            return;
        }

        List<Scope> scopes = toScopes(options.optJSONArray("scopes"));
        if (scopes.isEmpty()) {
            for (String scope : BASIC_SCOPES) {
                scopes.add(new Scope(scope));
            }
        }

        RevokeAccessRequest request = RevokeAccessRequest.builder()
                .setAccount(new Account(email, ACCOUNT_TYPE))
                .setScopes(scopes)
                .build();

        authorizationClient().revokeAccess(request)
                .addOnSuccessListener(unused -> signOut(callbackContext))
                .addOnFailureListener(e -> {
                    String message = e.getMessage();
                    if (message != null && message.contains("28404")) {
                        // Google answers the on-device revocation with HTTP 400, and tokens issued on the
                        // device cannot be revoked through the OAuth endpoint either, so there is no fallback.
                        Log.w(TAG, "revokeAccess rejected by Google", e);
                        fail(callbackContext, UNKNOWN, "Google rejected the revocation ([28404]). Revoke the "
                                + "refresh token from your backend, or ask the user to remove the app at "
                                + "https://myaccount.google.com/connections",
                                e instanceof ApiException ? ((ApiException) e).getStatusCode() : null);
                        return;
                    }
                    failApi(callbackContext, e, null);
                });
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private AuthorizationClient authorizationClient() {
        return Identity.getAuthorizationClient(cordova.getActivity());
    }

    private String webClientId(JSONObject options, CallbackContext callbackContext) {
        String clientId = optString(options, "webClientId");
        if (clientId == null) {
            clientId = preferences.getString(CONFIG_WEB_CLIENT_ID, null);
        }
        if (clientId == null || clientId.trim().isEmpty() || clientId.startsWith("$")) {
            fail(callbackContext, CONFIGURATION_ERROR,
                    "WEB_CLIENT_ID is not configured. Reinstall the plugin with --variable WEB_CLIENT_ID=...", null);
            return null;
        }
        return clientId.trim();
    }

    private SharedPreferences store() {
        return cordova.getContext().getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE);
    }

    private String storedEmail() {
        return store().getString(STORE_EMAIL, null);
    }

    private void rememberEmail(String email) {
        store().edit().putString(STORE_EMAIL, email).apply();
    }

    private static List<Scope> toScopes(JSONArray array) {
        List<Scope> scopes = new ArrayList<>();
        if (array == null) {
            return scopes;
        }
        for (int i = 0; i < array.length(); i++) {
            String scope = array.optString(i, "").trim();
            if (!scope.isEmpty()) {
                scopes.add(new Scope(scope));
            }
        }
        return scopes;
    }

    /** Like {@link JSONObject#optString(String)} but returns null for missing, null or empty values. */
    private static String optString(JSONObject object, String key) {
        if (object == null || object.isNull(key)) {
            return null;
        }
        String value = object.optString(key, "");
        return value.isEmpty() ? null : value;
    }

    private static void putIfNotNull(JSONObject object, String key, String value) throws JSONException {
        if (value != null) {
            object.put(key, value);
        }
    }

    private static String credentialErrorCode(GetCredentialException e) {
        if (e instanceof GetCredentialCancellationException) {
            return CANCELLED;
        }
        if (e instanceof NoCredentialException) {
            return NO_CREDENTIAL;
        }
        if (e instanceof GetCredentialInterruptedException) {
            return INTERRUPTED;
        }
        if (e instanceof GetCredentialProviderConfigurationException) {
            return CONFIGURATION_ERROR;
        }
        if (e instanceof GetCredentialUnsupportedException) {
            return NOT_SUPPORTED;
        }
        // Google reports a misconfigured project only in the message: "[28444] Developer console is not
        // set up correctly." (Android client ID used as WEB_CLIENT_ID, or wrong package name/SHA-1).
        String message = e.getMessage();
        if (message != null && message.contains("28444")) {
            return CONFIGURATION_ERROR;
        }
        return UNKNOWN;
    }

    private static void failApi(CallbackContext callbackContext, Exception e, JSONObject user) {
        String code = UNKNOWN;
        Object nativeError = null;
        if (e instanceof ApiException) {
            int status = ((ApiException) e).getStatusCode();
            nativeError = status;
            switch (status) {
                case CommonStatusCodes.CANCELED:
                case 12501: // GoogleSignInStatusCodes.SIGN_IN_CANCELLED
                    code = CANCELLED;
                    break;
                case CommonStatusCodes.NETWORK_ERROR:
                    code = NETWORK_ERROR;
                    break;
                case CommonStatusCodes.DEVELOPER_ERROR:
                    code = CONFIGURATION_ERROR;
                    break;
                case CommonStatusCodes.SIGN_IN_REQUIRED:
                    code = NOT_SIGNED_IN;
                    break;
                default:
                    break;
            }
        }
        Log.w(TAG, "Google Play services request failed", e);
        fail(callbackContext, code, e.getMessage(), nativeError, user);
    }

    private static void fail(CallbackContext callbackContext, String code, String message, Object nativeError) {
        fail(callbackContext, code, message, nativeError, null);
    }

    /**
     * @param user user already signed in by signIn() when only its authorization step failed, or null.
     *             The app receives it as {@code error.user}.
     */
    private static void fail(CallbackContext callbackContext, String code, String message, Object nativeError,
                             JSONObject user) {
        JSONObject error = new JSONObject();
        try {
            error.put("code", code);
            error.put("message", message != null ? message : code);
            if (nativeError != null) {
                error.put("nativeError", nativeError);
            }
            if (user != null) {
                error.put("user", user);
            }
        } catch (JSONException ignored) {
            // Unreachable: put() only throws for a null key or a non-finite number.
        }
        callbackContext.error(error);
    }
}
