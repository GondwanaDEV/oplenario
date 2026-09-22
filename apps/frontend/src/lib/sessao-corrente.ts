// A pergunta "há sessão agora?" / "qual é a próxima?" — derivação PURA sobre a listagem de GET /sessoes.
//
// Extraído de meu-painel-vista.ts (onde nasceu, para a home do vereador) quando a home da SECRETARIA
// (`inicio-vista.ts`) passou a precisar da mesma resposta: são duas telas perguntando a mesma coisa, e a
// regra — sobretudo "ao vivo inclui `suspensa`" e "próxima filtra por ESTADO, não só por data" — não pode
// divergir entre elas. Uma fonte de verdade, como manda a disciplina de consistência do CLAUDE.md.
//
// Segue puro e sem IO: recebe a lista já camelizada (SessaoOut do codegen) e devolve a sessão, nunca busca.

import type { SessaoOut } from "./contrato-sessoes.gen";

/** Espelha oplenario.sessoes.logic (a máquina de estados de SessaoOut.estado, §22.6): "aberta" e "suspensa"
 * são os dois estados em que a sessão está VIVA (suspensa = pausada, volta com "reabrir"); é esse o "há
 * sessão agora?" que as homes precisam responder, não só "aberta". */
export const ESTADOS_SESSAO_AO_VIVO = new Set(["aberta", "suspensa"]);

/** A sessão de `estado` "agendada" com menor data FUTURA (> `agoraIso`, estrito); `null` se nenhuma. Filtra
 * por ESTADO, não só por data: `agendada -> nao_realizada` é transição legal da máquina
 * (`sessoes/logic.clj`, `transicoes-sessao`) e NÃO apaga `agendada-para` — uma sessão cancelada por luto ou
 * falta de quórum continua com a data futura na projeção. Sem o filtro de estado, esta função reabre o
 * defeito #16 por outro campo: a home anunciaria como "próxima sessão" uma sessão que a Mesa já cancelou.
 * O mesmo raciocínio vale para `arquivada` chegando via `nao_realizada` — nenhum estado fora de "agendada"
 * é candidato a "próxima". */
export function proximaSessaoFutura(sessoes: SessaoOut[], agoraIso: string): SessaoOut | null {
  const futuras = sessoes.filter(
    (s): s is SessaoOut & { agendadaPara: string } =>
      s.estado === "agendada" && s.agendadaPara != null && s.agendadaPara > agoraIso
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
export function sessaoAoVivoEm(sessoes: SessaoOut[]): SessaoOut | null {
  return sessoes.find((s) => ESTADOS_SESSAO_AO_VIVO.has(s.estado)) ?? null;
}
