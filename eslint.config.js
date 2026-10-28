const js = require('@eslint/js');
const globals = require('globals');

module.exports = [
    {
        ignores: ['node_modules/', 'example/node_modules/', 'example/platforms/', 'example/plugins/']
    },
    js.configs.recommended,
    {
        files: ['www/**/*.js'],
        languageOptions: {
            ecmaVersion: 2017,
            sourceType: 'commonjs',
            globals: { ...globals.browser }
        }
    },
    {
        files: ['example/www/js/**/*.js'],
        languageOptions: {
            ecmaVersion: 2020,
            sourceType: 'script',
            globals: { ...globals.browser }
        }
    },
    {
        files: ['eslint.config.js', 'example/scripts/**/*.js'],
        languageOptions: {
            sourceType: 'commonjs',
            globals: { ...globals.node }
        }
    }
];
