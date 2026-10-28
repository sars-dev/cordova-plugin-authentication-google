var exec = require('cordova/exec');

var SERVICE = 'GoogleAuth';

var ErrorCode = Object.freeze({
    CANCELLED: 'CANCELLED',
    NO_CREDENTIAL: 'NO_CREDENTIAL',
    NOT_SIGNED_IN: 'NOT_SIGNED_IN',
    CONFIGURATION_ERROR: 'CONFIGURATION_ERROR',
    NETWORK_ERROR: 'NETWORK_ERROR',
    INVALID_ARGUMENT: 'INVALID_ARGUMENT',
    INTERRUPTED: 'INTERRUPTED',
    IN_PROGRESS: 'IN_PROGRESS',
    NOT_SUPPORTED: 'NOT_SUPPORTED',
    UNKNOWN: 'UNKNOWN'
});

// Builds a real Error (so it keeps a stack trace) whose prototype makes `instanceof GoogleAuthError` work.
// It can be called with or without `new`.
function GoogleAuthError (code, message, nativeError) {
    var error = new Error(message || code);
    Object.setPrototypeOf(error, GoogleAuthError.prototype);
    error.name = 'GoogleAuthError';
    error.code = code;
    if (nativeError !== undefined) {
        error.nativeError = nativeError;
    }
    return error;
}

GoogleAuthError.prototype = Object.create(Error.prototype, {
    constructor: { value: GoogleAuthError, writable: true, configurable: true }
});

function toError (raw) {
    if (raw && typeof raw === 'object' && raw.code) {
        var error = new GoogleAuthError(raw.code, raw.message, raw.nativeError);
        if (raw.user) {
            // signIn() with scopes: the user is signed in but the authorization step failed.
            error.user = raw.user;
        }
        return error;
    }
    return new GoogleAuthError(ErrorCode.UNKNOWN, typeof raw === 'string' ? raw : 'Unknown error');
}

function call (action, args) {
    return new Promise(function (resolve, reject) {
        exec(resolve, function (raw) { reject(toError(raw)); }, SERVICE, action, args || []);
    });
}

function normalizeOptions (options) {
    if (options === undefined || options === null) {
        return {};
    }
    if (typeof options !== 'object') {
        throw new GoogleAuthError(ErrorCode.INVALID_ARGUMENT, 'Options must be an object');
    }
    if (options.scopes !== undefined && !Array.isArray(options.scopes)) {
        throw new GoogleAuthError(ErrorCode.INVALID_ARGUMENT, 'options.scopes must be an array of strings');
    }
    return options;
}

function withOptions (action) {
    return function (options) {
        try {
            return call(action, [normalizeOptions(options)]);
        } catch (e) {
            return Promise.reject(e);
        }
    };
}

// The account picker and the consent screen can only be shown one at a time, so calls that may show
// them (or act on the signed-in account) are rejected with IN_PROGRESS while another one is running.
var busy = false;

function exclusive (method) {
    return function () {
        if (busy) {
            return Promise.reject(new GoogleAuthError(ErrorCode.IN_PROGRESS,
                'Another Google sign-in operation is in progress'));
        }
        busy = true;
        return method.apply(null, arguments).then(function (result) {
            busy = false;
            return result;
        }, function (error) {
            busy = false;
            throw error;
        });
    };
}

function authorize (options) {
    try {
        options = normalizeOptions(options);
        if (!options.scopes || options.scopes.length === 0) {
            throw new GoogleAuthError(ErrorCode.INVALID_ARGUMENT, 'authorize() requires at least one scope');
        }
        return call('authorize', [options]);
    } catch (e) {
        return Promise.reject(e);
    }
}

module.exports = {
    ErrorCode: ErrorCode,
    GoogleAuthError: GoogleAuthError,

    /** Shows the Google account picker and returns the signed-in user. */
    signIn: exclusive(withOptions('signIn')),

    /** Restores the last session without showing the account picker; rejects with NO_CREDENTIAL if there is none. */
    signInSilently: exclusive(withOptions('signInSilently')),

    /** Requests OAuth scopes and returns an access token and, optionally, a server auth code. */
    authorize: exclusive(authorize),

    /** Clears the local session so the next signIn() shows the account picker again. */
    signOut: function () {
        return call('signOut', []);
    },

    /** Revokes the access granted to this app and signs the user out. */
    revokeAccess: exclusive(withOptions('revokeAccess'))
};
