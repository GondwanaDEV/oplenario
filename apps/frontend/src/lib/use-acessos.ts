"use client";

// Os ACESSOS que o administrador da Casa concedeu — e agora revoga (ADR-0005, adendo "Revogar acesso"). A lista vem de
// GET /identidade/acessos (vereador, controle interno e jurídico, ativos e revogados, nome nunca CPF); revogar é
// POST /identidade/acessos/:id/revogacao com o papel e o MOTIVO. Mesmo desenho de use-vereadores.ts (leitura com token
// de refetch) e de use-setores.ts (escritas que devolvem a frase da tela, não o corpo): o backend decide, aqui só se diz
// a recusa em português.

import { useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";

export type AcessoOut = {
  identidadeId: string;
  nome: string;
  papel: string;
  concedidoEm: string;
  revogadoEm: string | null;
  revogadoPorNome: string | null;
  motivo: string | null;
};

/** O papel como o servidor da Câmara o conhece. Só os que a tela concede. */
export const ROTULO_DO_ACESSO: Record<string, string> = {
  vereador: "Vereador(a)",
  auditor: "Controle interno",
  juridico: "Jurídico",
};

export const rotuloDoAcesso = (papel: string): string => ROTULO_DO_ACESSO[papel] ?? "Acesso";

/** O vínculo que o backend espera em cada papel (o papel acompanha o tipo do vínculo). */
export const TIPO_DE_VINCULO_DO_PAPEL: Record<string, "vereador" | "servidor"> = {
  vereador: "vereador",
  auditor: "servidor",
  juridico: "servidor",
};

export const MOTIVO_MINIMO = 3;
export const MOTIVO_MAXIMO = 500;

function formaValida(d: unknown): d is { acessos: AcessoOut[] } {
  return (
    !!d &&
    typeof d === "object" &&
    Array.isArray((d as { acessos?: unknown }).acessos) &&
    (d as { acessos: unknown[] }).acessos.every(
      (a) => !!a && typeof a === "object" && typeof (a as AcessoOut).identidadeId === "string" && typeof (a as AcessoOut).papel === "string",
    )
  );
}

type Estado = "carregando" | "pronto" | "erro";

export function useAcessos(token: string | null, versao = 0) {
  const [dados, setDados] = useState<AcessoOut[]>([]);
  const [estado, setEstado] = useState<Estado>("carregando");

  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/identidade/acessos", { token: token ?? undefined, cache: "no-store" });
        if (!vivo) return;
        if (!r.ok) {
          setEstado("erro");
          return;
        }
        const corpo = camelizarChaves(await r.json());
        if (!vivo) return;
        if (!formaValida(corpo)) {
          setEstado("erro");
          return;
        }
        setDados(corpo.acessos);
        setEstado("pronto");
      } catch {
        if (vivo) setEstado("erro");
      }
    })();
    return () => {
      vivo = false;
    };
  }, [token, versao]);

  if (semCredencial(token)) return { dados: [] as AcessoOut[], estado: "erro" as Estado };
  return { dados, estado };
}

export type Resultado = { ok: true; vinculoEncerrado: boolean } | { ok: false; mensagem: string };

function mensagemDaRevogacao(status: number, erro?: string): string {
  if (status === 403) return "Só o administrador da Casa revoga acesso.";
  if (status === 404) return "Esse acesso já não está ativo. Atualize a página para ver a situação de agora.";
  if (status === 400) return "Informe o motivo da revogação (de 3 a 500 caracteres).";
  if (status === 423) return "A Câmara está com o acesso restrito: não é possível mexer em acessos agora.";
  return erro ? erro : "Não foi possível revogar o acesso. Tente de novo em instantes.";
}

/** Revoga o PAPEL da pessoa com o motivo. O motivo vai aparado; a validação de verdade é do backend. */
export async function revogarAcesso(
  token: string | null,
  identidadeId: string,
  papel: string,
  motivo: string,
): Promise<Resultado> {
  if (semCredencial(token)) return { ok: false, mensagem: "Sua sessão acabou. Entre de novo para continuar." };
  try {
    const r = await apiFetch(`/api/identidade/acessos/${encodeURIComponent(identidadeId)}/revogacao`, {
      token: token ?? undefined,
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ papel, motivo: motivo.trim() }),
    });
    const corpo = (await r.json().catch(() => ({}))) as { erro?: unknown; "vinculo-encerrado"?: unknown };
    if (!r.ok) return { ok: false, mensagem: mensagemDaRevogacao(r.status, typeof corpo.erro === "string" ? corpo.erro : undefined) };
    return { ok: true, vinculoEncerrado: corpo["vinculo-encerrado"] === true };
  } catch {
    return { ok: false, mensagem: "Sem conexão com o servidor. Tente de novo em instantes." };
  }
}

/** Dá o acesso de novo a quem já o teve (vereador ou controle interno): o mesmo passo final de sempre, com o e-mail do convite. */
export async function concederDeNovo(
  token: string | null,
  identidadeId: string,
  papel: string,
  email: string,
): Promise<{ ok: true } | { ok: false; mensagem: string }> {
  if (semCredencial(token)) return { ok: false, mensagem: "Sua sessão acabou. Entre de novo para continuar." };
  try {
    const r = await apiFetch("/api/identidade/acessos", {
      token: token ?? undefined,
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        "identidade-id": identidadeId,
        tipo: TIPO_DE_VINCULO_DO_PAPEL[papel] ?? "servidor",
        papeis: [papel],
        email: email.trim(),
      }),
    });
    if (r.ok) return { ok: true };
    const corpo = (await r.json().catch(() => ({}))) as { erro?: unknown };
    if (r.status === 409) {
      return { ok: false, mensagem: "O vínculo desta pessoa está suspenso. Reativar é outra operação: fale com o operador da plataforma." };
    }
    if (r.status === 400) return { ok: false, mensagem: "Confira o e-mail informado." };
    return { ok: false, mensagem: typeof corpo.erro === "string" ? corpo.erro : "Não foi possível dar o acesso de novo. Tente de novo em instantes." };
  } catch {
    return { ok: false, mensagem: "Sem conexão com o servidor. Tente de novo em instantes." };
  }
}
