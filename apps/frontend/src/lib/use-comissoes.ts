"use client";

// O CAMINHO DA COMISSÃO (ADR-0019, Eixo 6), lado da secretaria: listar as comissões da Casa, encaminhar a matéria a uma
// ou mais delas (abre um parecer por comissão) e designar o relator de um parecer. Antes disto o parecer de comissão só
// nascia na semente da demo. Authz real = backend (`secretario`); aqui só se traduz cada resposta em frase honesta.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type {
  ComissaoOut,
  ComissoesOut,
  DestinoDeComissao,
  ParecerAbertoOut,
  ParecerAbertosOut,
  RelatorDesignadoOut,
} from "./contrato-juridico.gen";

export type ResultadoComissao<T> = { ok: true; dado: T } | { ok: false; status: number; mensagem: string };

export const MAX_COMISSOES_POR_ENCAMINHAMENTO = 10;

export function mensagemDeErroComissao(status: number, acao: "listar" | "encaminhar" | "relator", erroDoServidor?: string): string {
  if (status === 0) return "Falha de rede. Nada foi gravado; tente de novo em instantes.";
  if (status === 401) return "Sua sessão expirou. Entre de novo.";
  if (status === 403) return "Só a secretaria encaminha a matéria às comissões e designa o relator.";
  if (status === 404) return acao === "relator" ? "Não encontramos o parecer ou o vereador escolhido." : "Não encontramos a matéria nesta Casa.";
  if (status === 409) return "O estado da matéria mudou e ela não pode mais ser encaminhada assim. Recarregue a ficha.";
  if (status === 400) return erroDoServidor ?? "Confira as comissões escolhidas e tente de novo.";
  return acao === "listar" ? "Não foi possível carregar as comissões." : "Não foi possível concluir agora. Tente de novo em instantes.";
}

async function chamar<T>(
  token: string | null,
  caminho: string,
  acao: "listar" | "encaminhar" | "relator",
  init: { method?: string; corpo?: unknown } = {},
  valido: (d: unknown) => boolean,
): Promise<ResultadoComissao<T>> {
  if (semCredencial(token)) return { ok: false, status: 401, mensagem: mensagemDeErroComissao(401, acao) };
  try {
    const r = await apiFetch(caminho, {
      token: token ?? undefined,
      cache: "no-store",
      method: init.method,
      ...(init.corpo !== undefined ? { headers: { "Content-Type": "application/json" }, body: JSON.stringify(init.corpo) } : {}),
    });
    const corpo = await r.json().catch(() => ({}));
    if (!r.ok) return { ok: false, status: r.status, mensagem: mensagemDeErroComissao(r.status, acao, (corpo as { erro?: string })?.erro) };
    const dado = camelizarChaves(corpo);
    if (!valido(dado)) return { ok: false, status: r.status, mensagem: mensagemDeErroComissao(500, acao) };
    return { ok: true, dado: dado as T };
  } catch {
    return { ok: false, status: 0, mensagem: mensagemDeErroComissao(0, acao) };
  }
}

export type CargaComissoes =
  | { fase: "carregando" }
  | { fase: "pronto"; comissoes: ComissaoOut[] }
  | { fase: "erro"; mensagem: string };

/** As comissões da Casa — só busca quando `ativo` (o diálogo aberto), para a ficha não pagar a chamada à toa. */
export function useComissoes(token: string | null, ativo: boolean): CargaComissoes {
  const [res, setRes] = useState<CargaComissoes>({ fase: "carregando" });
  useEffect(() => {
    if (!ativo) return;
    let vivo = true;
    (async () => {
      const r = await chamar<ComissoesOut>(token, "/api/legislativo/comissoes", "listar", {}, (d) => Array.isArray((d as ComissoesOut)?.comissoes));
      if (!vivo) return;
      setRes(r.ok ? { fase: "pronto", comissoes: r.dado.comissoes } : { fase: "erro", mensagem: r.mensagem });
    })();
    return () => {
      vivo = false;
    };
  }, [token, ativo]);
  return res;
}

/** POST .../pareceres-de-comissao — 1 a 10 comissões, cada uma com relator opcional. */
export function encaminharAsComissoes(token: string | null, proposicaoId: string, destinos: DestinoDeComissao[]) {
  return chamar<ParecerAbertosOut>(
    token,
    `/api/legislativo/proposicoes/${encodeURIComponent(proposicaoId)}/pareceres-de-comissao`,
    "encaminhar",
    {
      method: "POST",
      corpo: { comissoes: destinos.map((d) => ({ "comissao-id": d.comissaoId, "relator-id": d.relatorId })) },
    },
    (d) => Array.isArray((d as ParecerAbertosOut)?.pareceres),
  );
}

/** POST .../pareceres/:id/relator */
export function designarRelator(token: string | null, parecerId: string, relatorId: string) {
  return chamar<RelatorDesignadoOut>(
    token,
    `/api/legislativo/pareceres/${encodeURIComponent(parecerId)}/relator`,
    "relator",
    { method: "POST", corpo: { "relator-id": relatorId } },
    (d) => typeof (d as RelatorDesignadoOut)?.id === "string",
  );
}

/** A frase do resultado do encaminhamento: quantas comissões abriram parecer novo e quais já tinham um em andamento. */
export function resumoDoEncaminhamento(pareceres: ParecerAbertoOut[]): string {
  const novos = pareceres.filter((p) => !p.jaExistia);
  const antigos = pareceres.filter((p) => p.jaExistia);
  const partes: string[] = [];
  if (novos.length > 0) partes.push(`Parecer aberto em ${novos.map((p) => p.comissaoNome).join(", ")}.`);
  if (antigos.length > 0)
    partes.push(`${antigos.map((p) => p.comissaoNome).join(", ")} já ${antigos.length === 1 ? "tinha" : "tinham"} parecer em andamento — mantido, nada foi duplicado.`);
  return partes.join(" ");
}
