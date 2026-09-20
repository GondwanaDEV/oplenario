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

// `resolverCasa` é o primitivo: distingue "esta Casa NÃO existe" de "não consegui resolver agora".
// Motivo (achado do teste exploratório contra a homolog, método docs/20): `buscarNomeCasa` colapsava os
// dois casos em `null`, e a CAPA (`/portal/casa/[ente]`) degradava pro slug cru nos dois — renderizando o
// Portal do Cidadão INTEIRO para um ente inexistente ou malformado, com o id cru no cabeçalho e no
// `© 2026 <slug>` do rodapé. Um link errado exibia um portal de transparência crível para uma Casa que
// não existe. 404 (uuid bem-formado, sem Casa) e 400 (id malformado) são ambos veredito definitivo de
// "não existe"; qualquer outra falha (5xx, timeout, rede) é transitória e MANTÉM a degradação documentada.
export type ResolucaoCasa =
  | { estado: "ok"; nomeOficial: string; nomeCurto?: string }
  | { estado: "inexistente" }
  | { estado: "indisponivel" };

export async function resolverCasa(ente: string): Promise<ResolucaoCasa> {
  try {
    const r = await fetch(`${backend}/portal/casa/${codificarSegmento(ente)}`, { cache: "no-store" });
    if (r.status === 404 || r.status === 400) return { estado: "inexistente" };
    if (!r.ok) return { estado: "indisponivel" };
    const c = camelizarChaves(await r.json()) as { nomeOficial: string; nomeCurto?: string };
    return { estado: "ok", nomeOficial: c.nomeOficial, nomeCurto: c.nomeCurto };
  } catch {
    return { estado: "indisponivel" };
  }
}

// Contrato preservado VERBATIM (null p/ qualquer não-ok) — as telas internas (matéria, vereador) degradam
// pro slug de propósito: elas já têm o seu próprio "não encontrado" para o objeto que exibem, e o nome da
// Casa ali é moldura, não o assunto. Só a CAPA precisa do veredito, e usa `resolverCasa`.
export async function buscarNomeCasa(
  ente: string,
): Promise<{ nomeOficial: string; nomeCurto?: string } | null> {
  const r = await resolverCasa(ente);
  return r.estado === "ok" ? { nomeOficial: r.nomeOficial, nomeCurto: r.nomeCurto } : null;
}
