// View-model puro da home do vereador (Onda C1, estado fora-de-sessão da vereador-app.html) — 100%
// testável sem rede: agrupa/ordena o que já veio de /meu/painel + deriva "há sessão agora?" e "qual é a
// próxima?" a partir da listagem de sessões do ente (GET /sessoes, defeito #16 MATA — carry fechado:
// antes desta rota existir, `sessoes` era SEMPRE `[]` e a home tratava "não sei" como "não há", mesmo com
// uma sessão ABERTA e uma AGENDADA existindo ao mesmo tempo). Comparação de datas é lexicográfica de
// ISO-8601 (mesmo idioma de ficha-materia-vista.ts/mesa-vista.ts — os timestamps do backend são sempre UTC
// 'Z', então ordenar como string == ordenar como data). NENHUM fetch aqui — os hooks (use-meu-painel/
// use-sessoes/use-acusar-ciencia) ficam no page.tsx. IMPORTANTE: esta função é pura e não sabe se `sessoes`
// já carregou de verdade — quem chama com `sessoes=[]` porque o fetch ainda está em voo (ou falhou) recebe
// de volta `proximaSessao`/`sessaoAoVivo` = `null`, EXATAMENTE como receberia se a lista estivesse mesmo
// vazia. A distinção "carregando"/"erro"/"de fato nenhuma" NÃO é responsabilidade deste módulo — é do
// chamador (page.tsx), que tem o `estado` do hook de sessões e não pode jogá-lo fora antes de decidir o
// texto.
//
// SessaoOut vem do CODEGEN (contrato-sessoes.gen.ts, camelCase — GET /sessoes passa por apiFetch +
// camelizarChaves, mesmo boundary de use-folha.ts/use-chamada.ts). NÃO confundir com o `SessaoOut`
// kebab-case de `./contrato` (GET /sessoes/:id, hand-rolled, wire cru) — são dois tipos com o mesmo nome
// em arquivos diferentes; este módulo usa o gerado.

import type {
  MeuPainelOut,
  ProposicaoResumoMeuPainelOut,
  ParecerResumoMeuPainelOut,
  CienciaPendenteOut,
} from "./contrato-legislativo.gen";
import type { SessaoOut } from "./contrato-sessoes.gen";

// Espelha oplenario.legislativo.logic/estados-parecer-terminais (§22.4 eixo F) — os 4 desfechos que
// fecham um parecer. Vocabulário CRAVADO no backend (piso fixo do trigger); copiado aqui por não haver
// codegen de enum ainda (só de shape de dado) — se um 5º terminal entrar, o teste de "pareceres
// concluídos" deste arquivo é o sinal a atualizar.
const ESTADOS_PARECER_TERMINAIS = new Set(["aprovado", "rejeitado", "prejudicado", "prazo_vencido"]);

// Espelha oplenario.sessoes.logic (a máquina de estados de SessaoOut.estado, §22.6): "aberta" e "suspensa"
// são os dois estados em que a sessão está VIVA agora (a Mesa pode retomar uma suspensa sem reabrir) — é
// esse o "há sessão agora?" que a home precisa responder, não só "aberta".
const ESTADOS_SESSAO_AO_VIVO = new Set(["aberta", "suspensa"]);

export interface ParecerAgrupado {
  aguardando: ParecerResumoMeuPainelOut[];
  concluidos: ParecerResumoMeuPainelOut[];
}

export interface HomeVereadorVista {
  minhasProposicoes: ProposicaoResumoMeuPainelOut[];
  meusPareceres: ParecerAgrupado;
  ciencias: CienciaPendenteOut[];
  proximaSessao: SessaoOut | null;
  sessaoAoVivo: SessaoOut | null;
}

/**
 * `painel` (MeuPainelOut) + `sessoes` (a listagem de GET /sessoes) -> a home derivada. `painel`/`sessoes`
 * ausentes (fetch ainda não resolveu) -> estrutura vazia coerente, nunca lança. `agoraIso` OPCIONAL
 * (default = o relógio real) — só existe pra manter `proximaSessaoFutura` genuinamente pura (review react
 * LOW: sem isso, a função lia `new Date()` por dentro, então "puro, sem relógio" não era literalmente
 * verdade); os testes fixam `agoraIso` explícito.
 *
 * `sessoes=[]` aqui é AMBÍGUO por construção — pode ser "o ente não tem sessão nenhuma" ou "o fetch ainda
 * não voltou"/"falhou". Esta função não resolve essa ambiguidade (é pura, sem estado de rede); quem chama
 * com uma lista que ainda não é de confiança precisa guardar o `estado` do fetch à parte e não tratar
 * `proximaSessao`/`sessaoAoVivo` = `null` como prova de "de fato nenhuma" enquanto esse `estado` não for
 * "pronto" — ver `ProximaSessaoResumo` em page.tsx.
 */
export function derivarHome(
  painel: MeuPainelOut | null | undefined,
  sessoes: SessaoOut[] | null | undefined,
  agoraIso: string = new Date().toISOString()
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
    proximaSessao: proximaSessaoFutura(sessoes ?? [], agoraIso),
    sessaoAoVivo: sessaoAoVivoEm(sessoes ?? []),
  };
}

/** A sessão agendada de menor data FUTURA (>= `agoraIso`); `null` se nenhuma sessão futura agendada. */
function proximaSessaoFutura(sessoes: SessaoOut[], agoraIso: string): SessaoOut | null {
  const futuras = sessoes.filter(
    (s): s is SessaoOut & { agendadaPara: string } => s.agendadaPara != null && s.agendadaPara > agoraIso
  );
  if (futuras.length === 0) return null;
  return futuras.reduce((maisProxima, atual) =>
    atual.agendadaPara < maisProxima.agendadaPara ? atual : maisProxima
  );
}

/** A sessão em `estado` "aberta" ou "suspensa" (ver `ESTADOS_SESSAO_AO_VIVO`), se houver — a resposta a
 * "há sessão agora?". `find`, não `sort`: a ordem de `sessoes` é do SERVIDOR (ele já entrega
 * aberta/suspensa primeiro) e não deve ser reordenada no cliente; mas esta função não DEPENDE dessa
 * garantia — filtra por `estado`, então continua correta mesmo se a ordem mudar por engano no futuro. Em
 * teoria só existe UMA sessão viva por vez (a Mesa não abre duas simultaneamente); se por algum motivo
 * houvesse mais de uma, a primeira do array vence — sem preferência adicional. */
function sessaoAoVivoEm(sessoes: SessaoOut[]): SessaoOut | null {
  return sessoes.find((s) => ESTADOS_SESSAO_AO_VIVO.has(s.estado)) ?? null;
}
