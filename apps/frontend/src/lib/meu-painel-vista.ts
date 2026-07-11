// View-model puro da home do vereador (Onda C1, estado fora-de-sessão da vereador-app.html) — 100%
// testável sem rede: agrupa/ordena o que já veio de /meu/painel + deriva a "próxima sessão" a partir de
// uma lista de sessões (comparação lexicográfica de ISO-8601, mesmo idioma de ficha-materia-vista.ts/
// mesa-vista.ts — os timestamps do backend são sempre UTC 'Z', então ordenar como string == ordenar como
// data). NENHUM fetch aqui — os hooks (use-meu-painel/use-acusar-ciencia) ficam no page.tsx.

import type {
  MeuPainelOut,
  ProposicaoResumoMeuPainelOut,
  ParecerResumoMeuPainelOut,
  CienciaPendenteOut,
} from "./contrato-legislativo.gen";
import type { SessaoOut } from "./contrato";

// Espelha oplenario.legislativo.logic/estados-parecer-terminais (§22.4 eixo F) — os 4 desfechos que
// fecham um parecer. Vocabulário CRAVADO no backend (piso fixo do trigger); copiado aqui por não haver
// codegen de enum ainda (só de shape de dado) — se um 5º terminal entrar, o teste de "pareceres
// concluídos" deste arquivo é o sinal a atualizar.
const ESTADOS_PARECER_TERMINAIS = new Set(["aprovado", "rejeitado", "prejudicado", "prazo_vencido"]);

export interface ParecerAgrupado {
  aguardando: ParecerResumoMeuPainelOut[];
  concluidos: ParecerResumoMeuPainelOut[];
}

export interface HomeVereadorVista {
  minhasProposicoes: ProposicaoResumoMeuPainelOut[];
  meusPareceres: ParecerAgrupado;
  ciencias: CienciaPendenteOut[];
  proximaSessao: SessaoOut | null;
}

/**
 * `painel` (MeuPainelOut) + `sessoes` (lista, hoje sempre `[]` — não há endpoint de listagem de sessões
 * ainda, carry) -> a home derivada. `painel`/`sessoes` ausentes (fetch ainda não resolveu) -> estrutura
 * vazia coerente, nunca lança.
 */
export function derivarHome(
  painel: MeuPainelOut | null | undefined,
  sessoes: SessaoOut[] | null | undefined
): HomeVereadorVista {
  const proposicoes = [...(painel?.proposicoes ?? [])].sort((a, b) =>
    b.atualizadoEm.localeCompare(a.atualizadoEm)
  );
  const pareceres = painel?.pareceres ?? [];
  const meusPareceres: ParecerAgrupado = {
    aguardando: pareceres.filter((p) => !ESTADOS_PARECER_TERMINAIS.has(p.estado)),
    concluidos: pareceres.filter((p) => ESTADOS_PARECER_TERMINAIS.has(p.estado)),
  };
  return {
    minhasProposicoes: proposicoes,
    meusPareceres,
    ciencias: painel?.ciencias ?? [],
    proximaSessao: proximaSessaoFutura(sessoes ?? []),
  };
}

/** A sessão agendada de menor data FUTURA (agora em diante); `null` se nenhuma sessão futura agendada. */
function proximaSessaoFutura(sessoes: SessaoOut[]): SessaoOut | null {
  const agoraIso = new Date().toISOString();
  const futuras = sessoes.filter(
    (s): s is SessaoOut & { "agendada-para": string } =>
      s["agendada-para"] != null && s["agendada-para"] > agoraIso
  );
  if (futuras.length === 0) return null;
  return futuras.reduce((maisProxima, atual) =>
    atual["agendada-para"] < maisProxima["agendada-para"] ? atual : maisProxima
  );
}
