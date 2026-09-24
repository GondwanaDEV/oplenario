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
    // TZ: o fuso da Casa (beachhead Fortaleza), não o do runner. Sem isto a suíte roda em UTC e a classe
    // inteira de defeito "data ISO sem hora vira meia-noite UTC e recua um dia ao formatar" fica
    // INVISÍVEL — em UTC o resultado é acidentalmente certo. Foi assim que `formatarData("2026-08-15")`
    // devolvendo `14/08/2026` em Fortaleza passou despercebido até a verificação em browser (15/08/2026).
    // Um fuso a oeste cravado faz o teste poder reprovar; UTC faz a asserção não significar nada.
    env: { NEXT_PUBLIC_APP_ENV: "test", TZ: "America/Fortaleza" },
    // Sobe o teto do `waitFor`/`findBy*` do Testing Library — ver o porquê medido em vitest.setup.ts.
    setupFiles: ["./vitest.setup.ts"],
    // O teto do TESTE precisa caber os `waitFor` que ele faz. O setup dá 5s a cada `waitFor` (fome de event
    // loop no CI), e o default do Vitest para o teste inteiro também é 5s: um teste com dois `waitFor` em
    // sequência estourava o teste ANTES de o `waitFor` usar a janela que o setup lhe deu (24/09/2026:
    // `painel-tribuna` "+1 min concede 60s; Aparte concede aparte", "Test timed out in 5000ms" só no CI,
    // 12/12 verdes isolado). 15s = três janelas; um teste que de fato trava continua reprovando.
    testTimeout: 15000,
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
});
