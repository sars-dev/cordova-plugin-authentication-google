// Compile-only checks for types/index.d.ts. Run with `npm run typecheck`.
import type { GoogleAuthError, GoogleAuthPlugin, GoogleUser } from '../../types';

declare const cordova: { plugins: CordovaPlugins };

const GoogleAuth: GoogleAuthPlugin = cordova.plugins.GoogleAuth;

async function example(): Promise<void> {
    try {
        const user = await GoogleAuth.signIn({ nonce: 'random', scopes: ['https://www.googleapis.com/auth/drive.file'] });
        const id: string = user.id;
        const token: string | undefined = user.accessToken;
        console.log(id, token);

        const restored: GoogleUser = await GoogleAuth.signInSilently();
        console.log(restored.email);

        const auth = await GoogleAuth.authorize({ scopes: ['email'], offlineAccess: true });
        console.log(auth.accessToken, auth.serverAuthCode, auth.grantedScopes);

        await GoogleAuth.signOut();
        await GoogleAuth.revokeAccess();
    } catch (e) {
        const error = e as GoogleAuthError;
        if (error.code === GoogleAuth.ErrorCode.CANCELLED) {
            return;
        }
        if (error.code === GoogleAuth.ErrorCode.IN_PROGRESS) {
            return;
        }
        if (error.user) {
            const email: string = error.user.email;
            console.log(email);
        }
        throw error;
    }
}

void example();
