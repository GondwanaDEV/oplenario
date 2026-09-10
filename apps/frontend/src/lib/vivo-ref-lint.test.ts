import { describe, it, expect } from "vitest";
import { readdirSync, readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

// LINT ESTRUTURAL — o teste que impede o defeito T3-B de voltar (Trilha 3, ledger Fase 11).
//
// O defeito: os hooks de ESCRITA faziam `useEffect(() => () => { vivoRef.current = false; }, [])` e
// NUNCA re-armavam o ref. Sob React StrictMode — que roda em desenvolvimento — o cleanup executa no
// primeiro mount, entao `vivoRef.current` nasce `false` e TODO `setEstado(...)` posterior a resposta
// vira no-op. Efeito medido em browser: 1,5s depois de um `409 {"erro":"ja existe mandato vigente
// sobreposto..."}` o botao seguia "Salvando..." desabilitado e havia ZERO alerta na pagina — o
// usuario nao recebia pista nenhuma de que a Casa recusou a escrita.
//
// A contagem na epoca nao deixava duvida sobre o padrao: 8 hooks armavam, e eram TODOS de leitura;
// 19 so' desarmavam, e eram TODOS de escrita. Por isso o gate e' estrutural e nao um teste de
// comportamento: o defeito e' uma OMISSAO que se repete a cada hook novo, e um teste por hook seria
// esquecido exatamente do mesmo jeito que o `vivoRef.current = true` foi.
//
// Este teste REPROVA se qualquer `use-*.ts` desarmar o ref sem armar.
describe("lint: todo hook que desarma vivoRef tem de re-armar no mount", () => {
  const dir = dirname(fileURLToPath(import.meta.url));

  it("nenhum hook desarma sem armar", () => {
    const arquivos = readdirSync(dir).filter((f) => f.startsWith("use-") && f.endsWith(".ts") && !f.endsWith(".test.ts"));
    // Guarda contra o proprio gate virar vacuo: se o glob parar de achar arquivos (renomeacao de pasta,
    // mudanca de convencao), o teste passaria alegremente sem inspecionar nada. Afirma o volume.
    expect(arquivos.length, "o lint nao encontrou hooks para inspecionar — o gate virou vacuo").toBeGreaterThan(20);

    const infratores = arquivos.filter((f) => {
      const src = readFileSync(resolve(dir, f), "utf8");
      return src.includes("vivoRef.current = false") && !src.includes("vivoRef.current = true");
    });

    expect(infratores, `hooks que desarmam vivoRef sem re-armar (setEstado vira no-op sob StrictMode): ${infratores.join(", ")}`).toEqual([]);
  });
});
