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
    // NEXT_PUBLIC_APP_ENV: o modo de auth do FE tem default REAL (src/lib/modo.ts) — modo dev é opt-in
    // explícito, como no backend (APP_ENV). A suíte declara "test" p/ a maioria dos casos exercitar o modo
    // dev (token síncrono, sem /eu); quem prova o modo real sobrepõe com vi.stubEnv por teste. Sem isto o
    // default seguro colocaria a suíte inteira em modo real — que é a intenção do default, não da suíte.
    env: { NEXT_PUBLIC_APP_ENV: "test" },
    // Sobe o teto do `waitFor`/`findBy*` do Testing Library — ver o porquê medido em vitest.setup.ts.
    setupFiles: ["./vitest.setup.ts"],
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
});
