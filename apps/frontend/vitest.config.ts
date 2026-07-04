import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

// jsdom: os testes de lib puros (cronômetro, reducer, SSE) não precisam de DOM e continuam passando; o
// AuthContext (src/lib/auth.tsx) renderiza componentes React via @testing-library/react, que exige `document`.
//
// resolve.alias "@" → "./src": espelha o `paths` do tsconfig.json (que o Next.js já resolve nativamente
// no build/dev). Até o Task B2 nenhum arquivo com teste próprio importava via "@/..." (todos usavam
// caminho relativo, ex. auth.test.tsx → "./auth"); TopoInterno é o primeiro a importar "@/lib/tema" de um
// módulo testado, o que expôs a lacuna — sem isto o Vite (motor do Vitest) não sabe resolver o alias.
export default defineConfig({
  test: {
    environment: "jsdom",
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
});
