"use client";

// Acesso à AUDIÊNCIA PÚBLICA (ADR-0021 Parte A) pelas telas autenticadas: a Mesa (ler a audiência, ajustar o tempo e
// as inscrições, inscrever presencialmente, chamar, encerrar a fala, marcar ausente) e a área da cidadã (as minhas
// inscrições). Mesmo desenho de use-comunicados.ts: uma chamada autenticada (`pedirAudiencia`) que traduz cada status
// em frase da tela, e um carregador que refaz sob demanda sem piscar. A authz real é o backend (`secretario`, dono da
// inscrição): aqui só se diz a recusa em português. Uma rota que falha vira erro na tela, nunca uma lista vazia.
//
// As escritas da Mesa não leem a resposta (ver o cabeçalho de contrato-audiencia.ts): a tela recarrega a audiência.

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import {
  ROTAS_AUDIENCIA as R,
  corpoDoAjuste,
  doFio,
  formaValida,
  type AjusteAudienciaIn,
  type AudienciaOut,
  type MinhasInscricoesOut,
} from "./contrato-audiencia";

export type Resultado<T> = { ok: true; dado: T } | { ok: false; status: number; mensagem: string };

export type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro"; status: number; mensagem: string };

export type AcaoAudiencia = "carregar" | "ajustar" | "inscrever" | "chamar" | "encerrar" | "ausencia" | "minhas";

const RECUSA: Record<AcaoAudiencia, string> = {
  carregar: "A Mesa da audiência é da secretaria e dos vereadores.",
  ajustar: "Só a secretaria ajusta a audiência.",
  inscrever: "Só a secretaria inscreve presencialmente.",
  chamar: "Só a secretaria conduz as falas da audiência.",
  encerrar: "Só a secretaria conduz as falas da audiência.",
  ausencia: "Só a secretaria conduz as falas da audiência.",
  minhas: "Sua sessão não dá acesso às inscrições.",
};

const CONFLITO: Partial<Record<AcaoAudiencia, string>> = {
  chamar: "Não foi possível chamar agora: a sessão precisa estar aberta e ninguém pode estar com a palavra. Atualize a lista.",
  encerrar: "Esta fala já foi encerrada. Atualize a lista.",
  ausencia: "Só quem ainda espera a vez pode ser marcado ausente. Atualize a lista.",
  inscrever: "A audiência já terminou: não cabe mais inscrição.",
  ajustar: "A audiência já terminou: não dá mais para ajustá-la.",
};

function primeiraMaiuscula(s: string) {
  const t = s.trim();
  if (!t) return t;
  const frase = t.charAt(0).toUpperCase() + t.slice(1);
  return /[.!?]$/.test(frase) ? frase : `${frase}.`;
}

/** A frase da tela para cada falha. `erroDoServidor` só aparece onde ele explica algo que a pessoa pode corrigir. */
export function mensagemDeErroAudiencia(status: number, acao: AcaoAudiencia, erroDoServidor?: string): string {
  if (status === 0) return "Sem conexão com o servidor. Nada foi gravado; tente de novo.";
  if (status === 401) return "Sua sessão expirou. Entre de novo para continuar.";
  if (status === 403) return RECUSA[acao];
  if (status === 404) {
    return acao === "carregar" ? "Esta sessão não é uma audiência pública, ou não existe nesta Casa." : "Não encontramos esta inscrição. Atualize a lista.";
  }
  if (status === 410) return "Esta Câmara encerrou o uso do sistema. O acervo fica só para consulta.";
  if (status === 423) return "A Câmara está com acesso restrito: conduzir a audiência está suspenso por enquanto.";
  if ((status === 400 || status === 409 || status === 422) && erroDoServidor) return primeiraMaiuscula(erroDoServidor);
  if (status === 409) return CONFLITO[acao] ?? "O estado da audiência mudou. Atualize a lista.";
  if (status === 400 || status === 422) return "Algum dado não passou na conferência do servidor. Revise e tente de novo.";
  return acao === "carregar" || acao === "minhas"
    ? "Não foi possível carregar agora. Tente de novo em instantes."
    : "O servidor não conseguiu concluir agora. Tente de novo em instantes.";
}

/** Uma chamada autenticada, JSON nos dois sentidos. */
export async function pedirAudiencia<T>(
  token: string | null,
  caminho: string,
  acao: AcaoAudiencia,
  init: { method?: string; corpo?: unknown } = {},
  valido: (dado: unknown) => boolean = () => true,
  adaptar: (dado: unknown) => unknown = (d) => d,
): Promise<Resultado<T>> {
  if (semCredencial(token)) return { ok: false, status: 401, mensagem: mensagemDeErroAudiencia(401, acao) };
  try {
    const r = await apiFetch(caminho, {
      token: token ?? undefined,
      cache: "no-store",
      method: init.method,
      ...(init.corpo !== undefined ? { headers: { "Content-Type": "application/json" }, body: JSON.stringify(init.corpo) } : {}),
    });
    const corpo: unknown = await r.json().catch(() => ({}));
    if (!r.ok) {
      const erro = (corpo as { erro?: unknown })?.erro;
      return { ok: false, status: r.status, mensagem: mensagemDeErroAudiencia(r.status, acao, typeof erro === "string" ? erro : undefined) };
    }
    const dado = adaptar(camelizarChaves(corpo));
    if (!valido(dado)) return { ok: false, status: 500, mensagem: mensagemDeErroAudiencia(500, acao) };
    return { ok: true, dado: dado as T };
  } catch {
    return { ok: false, status: 0, mensagem: mensagemDeErroAudiencia(0, acao) };
  }
}

/** Carrega um GET e o refaz sob demanda SEM piscar (`recarregar` mantém o que está na tela até chegar o novo). */
function useCarregar<T>(token: string | null, caminho: string, acao: AcaoAudiencia, valido: (d: unknown) => boolean, adaptar: (d: unknown) => unknown) {
  const [carga, setCarga] = useState<{ caminho: string | null; estado: Carga<T> }>({ caminho: null, estado: { fase: "carregando" } });
  const [rev, setRev] = useState(0);
  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      const r = await pedirAudiencia<T>(token, caminho, acao, {}, valido, adaptar);
      if (!vivo) return;
      setCarga({ caminho, estado: r.ok ? { fase: "pronto", dado: r.dado } : { fase: "erro", status: r.status, mensagem: r.mensagem } });
    })();
    return () => {
      vivo = false;
    };
    // `acao`, `valido` e `adaptar` são constantes do chamador; refazer só por token, caminho ou pedido explícito
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, caminho, rev]);
  const estado: Carga<T> = semCredencial(token)
    ? { fase: "erro", status: 401, mensagem: mensagemDeErroAudiencia(401, acao) }
    : carga.caminho === caminho
      ? carga.estado
      : { fase: "carregando" };
  const recarregar = useCallback(() => setRev((n) => n + 1), []);
  return { estado, recarregar };
}

// ---- a Mesa ----

export function useAudienciaMesa(token: string | null, sessaoId: string) {
  return useCarregar<AudienciaOut>(token, R.audiencia(sessaoId), "carregar", formaValida.audiencia, doFio.audiencia);
}

export function ajustarAudiencia(token: string | null, sessaoId: string, ajuste: AjusteAudienciaIn) {
  return pedirAudiencia<unknown>(token, R.audiencia(sessaoId), "ajustar", { method: "PATCH", corpo: corpoDoAjuste(ajuste) });
}

/** O corpo já vem de `validarInscricaoPresencial` (kebab). */
export function inscreverPresencial(token: string | null, sessaoId: string, corpo: Record<string, unknown>) {
  return pedirAudiencia<unknown>(token, R.inscricoes(sessaoId), "inscrever", { method: "POST", corpo });
}

export function chamarInscrita(token: string | null, sessaoId: string, inscId: string) {
  return pedirAudiencia<unknown>(token, R.chamada(sessaoId, inscId), "chamar", { method: "POST", corpo: {} });
}

export function encerrarFala(token: string | null, sessaoId: string, inscId: string, tempoUsadoSegundos: number) {
  return pedirAudiencia<unknown>(token, R.encerramento(sessaoId, inscId), "encerrar", {
    method: "POST",
    corpo: { "tempo-usado-segundos": Math.max(0, Math.round(tempoUsadoSegundos)) },
  });
}

export function marcarAusente(token: string | null, sessaoId: string, inscId: string) {
  return pedirAudiencia<unknown>(token, R.ausencia(sessaoId, inscId), "ausencia", { method: "POST", corpo: {} });
}

// ---- a área da cidadã ----

export function useMinhasInscricoes(token: string | null) {
  return useCarregar<MinhasInscricoesOut>(token, R.minhas, "minhas", formaValida.minhas, doFio.minhas);
}
