// Cliente de leitura pública do Portal do Cidadão (Task 0.3, Fatia A2.0). Espelha `buscarOuNull` de
// use-mesa.ts, mas para a superfície SEM auth (nenhuma rota autenticada nesta fatia): sem header
// Authorization, `!ok` ou throw sempre viram `null` — "degradação por seção" (Global Constraints do
// plano): uma seção com fetch falho/vazio degrada sozinha, nunca derruba a página inteira.
//
// `buscarPublico` recebe SEGMENTOS de path (não um `caminho` já concatenado) — review de segurança
// A2.0: um `caminho` único concatenado direto na URL deixava passar `../` vindo de qualquer segmento
// não confiável (ex.: um `ente` da URL). Cada segmento é codificado individualmente antes do join.

import { camelizarChaves } from "./boundary";

function codificarSegmento(segmento: string): string {
  const codificado = encodeURIComponent(segmento);
  // encodeURIComponent não escapa "."/".." (não são reservados) — sem isso, um segmento igual a ".."
  // continuaria significando "sobe um nível" quando o navegador resolve a URL relativa, escapando do
  // prefixo /api/portal/casa/. Só os segmentos puramente de pontos precisam desse reforço.
  return /^\.+$/.test(codificado) ? codificado.replace(/\./g, "%2E") : codificado;
}

export async function buscarPublico<T>(...segmentos: string[]): Promise<T | null> {
  try {
    const caminho = segmentos.map(codificarSegmento).join("/");
    const r = await fetch(`/api/portal/casa/${caminho}`, { cache: "no-store" });
    if (!r.ok) return null;
    return camelizarChaves(await r.json()) as T;
  } catch {
    return null;
  }
}

// `buscarNomeCasa` roda em SERVER COMPONENT (page.tsx), NÃO em client — por isso não pode usar
// `buscarPublico` (fetch relativo `/api/...`, que só resolve no browser via o rewrite same-origin de
// next.config.ts). Aqui o fetch é direto ao backend com a MESMA env var (`BACKEND_URL`) que o rewrite usa
// como alvo — servidor-a-servidor, sem depender de origem de browser. Resolve o nome real da Câmara ANTES
// do primeiro paint (sem flash de UUID); falha/timeout -> null, e o caller (page.tsx) degrada pro slug cru
// da URL — nunca pior que o comportamento anterior a este fix.
const backend = process.env.BACKEND_URL ?? "http://localhost:8888";

export async function buscarNomeCasa(
  ente: string,
): Promise<{ nomeOficial: string; nomeCurto?: string } | null> {
  try {
    const r = await fetch(`${backend}/portal/casa/${codificarSegmento(ente)}`, { cache: "no-store" });
    if (!r.ok) return null;
    return camelizarChaves(await r.json()) as { nomeOficial: string; nomeCurto?: string };
  } catch {
    return null;
  }
}
