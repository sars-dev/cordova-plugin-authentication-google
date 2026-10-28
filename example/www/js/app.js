/* global cordova */

const SCOPES = ['https://www.googleapis.com/auth/userinfo.profile'];

const logElement = document.getElementById('log');

function log (label, value) {
    const text = value === undefined ? '' : JSON.stringify(value, null, 2);
    logElement.textContent = `[${new Date().toLocaleTimeString()}] ${label}\n${text}\n\n${logElement.textContent}`;
    console.log(label, value);
}

function shorten (result) {
    // Keep tokens readable in the log; the full values are in the Chrome DevTools console.
    const copy = { ...result };
    for (const key of ['idToken', 'accessToken', 'serverAuthCode']) {
        if (typeof copy[key] === 'string' && copy[key].length > 40) {
            copy[key] = `${copy[key].slice(0, 20)}…${copy[key].slice(-10)}`;
        }
    }
    return copy;
}

function showUser (user) {
    const section = document.getElementById('user');
    section.hidden = !user;
    if (user) {
        document.getElementById('photo').src = user.photoUrl || '';
        document.getElementById('name').textContent = user.displayName || '';
        document.getElementById('email').textContent = user.email;
    }
}

// In a real app the nonce comes from your backend, which checks it when verifying the ID token.
// This test app has no backend, so it only shows how the option is passed.
function randomNonce () {
    return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');
}

document.addEventListener('deviceready', () => {
    const GoogleAuth = cordova.plugins.GoogleAuth;

    const actions = {
        signIn: () => GoogleAuth.signIn({ nonce: randomNonce() }),
        signInButton: () => GoogleAuth.signIn({ mode: 'button', nonce: randomNonce() }),
        signInSilently: () => GoogleAuth.signInSilently(),
        authorize: () => GoogleAuth.authorize({ scopes: SCOPES, offlineAccess: true }),
        signOut: async () => {
            await GoogleAuth.signOut();
            showUser(null);
        },
        revokeAccess: async () => {
            await GoogleAuth.revokeAccess();
            showUser(null);
        }
    };

    for (const button of document.querySelectorAll('[data-action]')) {
        const name = button.getAttribute('data-action');
        button.disabled = false;
        button.addEventListener('click', async () => {
            log(`${name}() …`);
            try {
                const result = await actions[name]();
                if (result && result.idToken) {
                    showUser(result);
                }
                log(`${name}() ✓`, result ? shorten(result) : undefined);
            } catch (error) {
                log(`${name}() ✗ ${error.code}`, { message: error.message, nativeError: error.nativeError });
            }
        });
    }

    document.getElementById('clear').addEventListener('click', () => {
        logElement.textContent = '';
    });

    document.getElementById('status').textContent = `Plugin ready (${cordova.platformId})`;
}, false);
