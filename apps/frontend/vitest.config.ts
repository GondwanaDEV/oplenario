import { defineConfig } from "vitest/config";

// jsdom: os testes de lib puros (cronômetro, reducer, SSE) não precisam de DOM e continuam passando; o
// AuthContext (src/lib/auth.tsx) renderiza componentes React via @testing-library/react, que exige `document`.
export default defineConfig({
  test: {
    environment: "jsdom",
  },
});
