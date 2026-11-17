// Lint for the JavaScript this repository owns.
//
// Two kinds of file live here and they answer to different globals:
//
//   * the scripts injected into a page (`rust/assets/**`, `page_runtime.js`)
//     are classic scripts that run beside somebody else's site code, so their
//     globals are the browser's — plus `unsafeWindow`, which a userscript host
//     may hand in;
//   * the test tooling and its tests (`*.mjs`, `vitest.config.mjs`) run in
//     Node, and a test also hands callbacks to a real browser
//     (`page.evaluate`), so both vocabularies are in scope there.
//
// The website is a separate package with its own `vp lint`; it is not this
// tree's tooling and is ignored here.

import js from '@eslint/js';
import globals from 'globals';

export default [
  {
    ignores: [
      '**/node_modules/**',
      '**/.*/**',
      '**/build/**',
      'out/**',
      'rust/target/**',
      'website/**',
      'bench/**',
    ],
  },

  js.configs.recommended,

  {
    files: ['**/*.{js,mjs,cjs}'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      globals: { ...globals.node },
    },
    rules: {
      // `catch (e) { /* leave it as it came */ }` is how a script that must
      // never break the page swallows a failure; the binding names the
      // failure for the comment rather than for code that reads it.
      'no-unused-vars': ['error', { caughtErrors: 'none' }],
    },
  },

  // The injected scripts are classic scripts evaluated in the page.
  {
    files: ['rust/assets/**/*.js', 'rust/src/mitm/page_runtime.js'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'script',
      globals: { ...globals.browser, unsafeWindow: 'readonly' },
    },
    rules: {
      // A stub mirrors the vendor API it stands in for: `fetch(input, init)`
      // keeps `init` because that is the shape the page calls.
      'no-unused-vars': ['error', { args: 'none', caughtErrors: 'none' }],
    },
  },

  {
    files: ['**/*.test.mjs', 'vitest.config.mjs'],
    languageOptions: {
      globals: { ...globals.node, ...globals.browser },
    },
  },
];
