// (Re)installs the plugin from the parent folder so native changes reach the app.
// Usage: npm run plugin -- YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
//
// The plugin is packed with `npm pack` first: installing the parent folder directly would make
// Cordova copy this example app into itself, and the tarball is exactly what npm will publish.
const { execSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const PLUGIN_ID = 'cordova-plugin-authentication-google';
const exampleDir = path.join(__dirname, '..');
const pluginDir = path.join(exampleDir, '..');
const clientId = process.argv[2] || process.env.WEB_CLIENT_ID;

if (!clientId) {
    console.error('Missing Web client ID. Usage: npm run plugin -- YOUR_WEB_CLIENT_ID.apps.googleusercontent.com');
    process.exit(1);
}

function run (command, cwd) {
    return execSync(command, { cwd: cwd, stdio: ['ignore', 'pipe', 'inherit'] }).toString();
}

const packDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cordova-google-auth-'));
try {
    const tarball = run('npm pack --silent --pack-destination "' + packDir + '"', pluginDir).trim().split('\n').pop();
    // Cordova does not accept a .tgz path, so install from the extracted folder (npm packs into "package/").
    run('tar -xzf "' + tarball + '"', packDir);
    const packagePath = path.join(packDir, 'package');

    try {
        run('npx cordova plugin rm ' + PLUGIN_ID + ' --nosave --variable WEB_CLIENT_ID=' + clientId, exampleDir);
    } catch {
        // Not installed yet.
    }
    process.stdout.write(run('npx cordova plugin add "' + packagePath + '" --nosave --variable WEB_CLIENT_ID=' + clientId, exampleDir));
} finally {
    fs.rmSync(packDir, { recursive: true, force: true });
}
