import coreWebVitals from "eslint-config-next/core-web-vitals";
import typescriptRules from "eslint-config-next/typescript";

/** Flat config - eslint-config-next 16 ships flat configs directly. */
const eslintConfig = [
  { ignores: [".next/**", "node_modules/**", "playwright-report/**", "test-results/**", "next-env.d.ts"] },
  ...coreWebVitals,
  ...typescriptRules,
];

export default eslintConfig;
