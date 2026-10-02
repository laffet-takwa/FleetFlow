/* eslint-env node */
module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
  },
  parser: 'vue-eslint-parser',
  parserOptions: {
    parser: '@typescript-eslint/parser',
    ecmaVersion: 'latest',
    sourceType: 'module',
    extraFileExtensions: ['.vue'],
  },
  plugins: ['@typescript-eslint'],
  extends: [
    'eslint:recommended',
    'plugin:vue/vue3-recommended',
    'plugin:@typescript-eslint/recommended',
  ],
  rules: {
    // A single word is not a component name, and self-closing tags everywhere.
    'vue/multi-word-component-names': 'error',
    'vue/require-default-prop': 'off',

    // v-html is used deliberately, and only for inline SVG path data in the navigation.
    // The values are static literals declared in the component, never user input, so
    // this does not reintroduce an XSS sink. eslint-plugin-vue v9 takes no options for
    // this rule, so it cannot be scoped to <svg> alone.
    'vue/no-v-html': 'off',

    // Whitespace rules from the recommended config fight the way this codebase is
    // written: a one-line SVG path attribute is easier to read than six of them, and
    // there is no formatter in the toolchain to make the argument.
    'vue/max-attributes-per-line': 'off',
    'vue/singleline-html-element-content-newline': 'off',
    'vue/multiline-html-element-content-newline': 'off',
    'vue/html-self-closing': 'off',
    'vue/html-indent': 'off',
    'vue/html-closing-bracket-newline': 'off',
    'vue/attributes-order': 'off',

    // camelCase attributes throughout, so `fullWidth` and `stockStatus` read the same
    // in a template as they do in the script and in the prop declaration.
    'vue/attribute-hyphenation': ['error', 'never'],

    // The TypeScript compiler already covers unused bindings more precisely, and
    // the two disagree often enough to be noise.
    '@typescript-eslint/no-unused-vars': [
      'error',
      { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
    ],
    '@typescript-eslint/no-explicit-any': 'warn',

    eqeqeq: ['error', 'smart'],
    'no-console': ['warn', { allow: ['error'] }],
    'prefer-const': 'error',
    'object-shorthand': ['error', 'always'],
  },
  overrides: [
    {
      files: ['**/*.config.ts', 'vite.config.ts'],
      rules: {
        // Build configuration legitimately reaches for process.env.
        'no-console': 'off',
      },
    },
  ],
}