import js from '@eslint/js';
import hooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
export default [
  { ignores: ['dist/**', 'node_modules/**'] },
  js.configs.recommended,
  { files: ['**/*.{js,jsx}'], languageOptions: { globals: { ...globals.browser, ...globals.node }, parserOptions: { ecmaFeatures: { jsx: true } } }, rules: { 'no-unused-vars': ['error', { varsIgnorePattern: '^[A-Z]', caughtErrorsIgnorePattern: '^_' }] } },
  { files: ['src/**/*.{js,jsx}'], plugins: { 'react-hooks': hooks }, rules: {
    'react-hooks/rules-of-hooks': 'error', 'react-hooks/exhaustive-deps': 'error',
  } },
];
