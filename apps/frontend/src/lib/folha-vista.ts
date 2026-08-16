// View-model PURO da FOLHA DA SESSÃO (§22.6/Etapa 5, fatia 6) — sem IO, sem React, mesmo padrão de
// chamada-vista.ts / placar-vista.ts. `use-folha.ts` (IO) é o único chamador; `page.tsx` nunca reimplementa
// nada daqui.
//
// A REGRA MAIS IMPORTANTE: este módulo NUNCA reformata os hashes além de separar "algoritmo:dígito" —
// `formatarHash` não trunca nem recodifica o dígito, porque é exatamente esse valor que o jurídico compara
// bit a bit contra o que o `sha256sum` local produzir. Truncar "para caber na tela" seria mentir por omissão
// sobre a prova de integridade que a folha existe para carregar.

import type { FolhaMetadadosOut } from "./contrato-sessoes.gen";

// ---------- 1. ordenarVersoes ----------

/** Mais recente primeiro — a versão que a Mesa quer ver ao abrir a tela é a última congelada, não a
 * primeira histórica. Nunca muta a entrada (a mesma disciplina de `ordenarLinhas`/`agruparLinhas`). */
export function ordenarVersoes(folhas: FolhaMetadadosOut[]): FolhaMetadadosOut[] {
  return [...folhas].sort((a, b) => b.versao - a.versao);
}

// ---------- 2. formatarHash ----------

export interface HashExibivel {
  /** "sha256", ou "" quando o valor não carrega prefixo reconhecível (fail-closed: nunca lança). */
  algoritmo: string;
  /** O dígito, VERBATIM — nenhum caractere alterado, é o valor que se compara bit a bit. */
  digest: string;
}

/** `"sha256:9f86d0…"` -> `{algoritmo:"sha256", digest:"9f86d0…"}`. Sem `:` reconhecível, devolve o valor
 * inteiro como dígito (algoritmo vazio) — nunca lança, nunca descarta parte do hash. */
export function formatarHash(hash: string): HashExibivel {
  const i = hash.indexOf(":");
  if (i === -1) return { algoritmo: "", digest: hash };
  return { algoritmo: hash.slice(0, i), digest: hash.slice(i + 1) };
}

// ---------- 3. retryAfterSegundos ----------

/** Header `Retry-After` (string, quando presente) -> segundos, ou `null` — nunca `NaN` vazando pra tela. */
export function retryAfterSegundos(header: string | null | undefined): number | null {
  if (!header) return null;
  const n = Number(header);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

// ---------- 4. mensagemDeErroFolha ----------

/** Traduz o `{erro}` cru das 4 rotas da folha (`diplomat/http/in.clj`, `resposta-conflito-folha` +
 * `resposta-folha-conteudo`) na frase que a tela mostra — nunca o corpo cru, nunca "erro ao salvar", nunca
 * o nome interno de uma decisão (D6/D9/D7) vazando pro operador. Casa por `status` primeiro (a fonte mais
 * estável) e usa o texto do corpo só para desambiguar os DOIS 409 e os DOIS 404 possíveis. Corpo/status que
 * não casa com nada conhecido cai num texto genérico com o status — nunca lança. */
export function mensagemDeErroFolha(
  corpoErro: string | null | undefined,
  status: number,
  retryAfter?: number | null,
): string {
  const erro = corpoErro ?? "";

  if (status === 409) {
    if (/sessao FECHADA/i.test(erro)) {
      return "A folha só existe para uma sessão encerrada. Feche a sessão antes de gerar a folha de presença — esta ação não está disponível enquanto a sessão está em andamento.";
    }
    return "Duas pessoas tentaram congelar esta folha ao mesmo tempo — tente novamente.";
  }
  if (status === 413) {
    return "O conteúdo desta sessão excede o tamanho máximo suportado para gerar a folha. Fale com o suporte.";
  }
  if (status === 503) {
    const espera = retryAfter ? ` Tente novamente em ${retryAfter}s.` : " Tente novamente em instantes.";
    return `O gerador de documentos está temporariamente ocupado.${espera}`;
  }
  if (status === 403) {
    return "Você não tem permissão para ver a folha desta sessão — este documento é restrito ao papel de secretaria.";
  }
  if (status === 404) {
    if (/versao da folha/i.test(erro)) return "Esta versão da folha não foi encontrada.";
    return "Sessão não encontrada.";
  }
  if (status === 500 && /temporariamente indisponivel/i.test(erro)) {
    return "A folha foi congelada, mas o conteúdo está temporariamente indisponível. Tente novamente em instantes.";
  }
  return erro || `Falha ao processar a folha (status ${status}).`;
}
