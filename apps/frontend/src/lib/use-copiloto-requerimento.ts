"use client";

// O copiloto do requerimento (Faixa B / B.7): POST /api/meu/requerimentos/copiloto com o pedido em palavras. Estado
// próprio (não mexe no envio do formulário): enquanto o assistente monta, o formulário continua utilizável. Os
// `campos` voltam com as chaves do modelo INTACTAS — são nomes de placeholder, não chaves de contrato.

import { useCallback, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { CopilotoRequerimentoOut } from "./contrato-legislativo.gen";
import { lerResultadoCopiloto, mensagemDeErroCopiloto, type ResultadoCopiloto } from "./copiloto-requerimento-vista";

export async function pedirAoCopiloto(token: string | null, descricao: string): Promise<ResultadoCopiloto> {
  try {
    const r = await apiFetch("/api/meu/requerimentos/copiloto", {
      token: token ?? undefined,
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ descricao }),
      cache: "no-store",
    });
    if (!r.ok) return { tipo: "nada", mensagem: mensagemDeErroCopiloto(r.status) };
    const bruto = (await r.json()) as { preenchimento?: { campos?: Record<string, string> } | null };
    const resposta = camelizarChaves(bruto) as CopilotoRequerimentoOut;
    if (resposta.preenchimento && bruto.preenchimento?.campos) resposta.preenchimento.campos = bruto.preenchimento.campos;
    return lerResultadoCopiloto(resposta);
  } catch {
    return { tipo: "nada", mensagem: mensagemDeErroCopiloto(0) };
  }
}

export function useCopilotoRequerimento(token: string | null) {
  const [montando, setMontando] = useState(false);
  const pedir = useCallback(
    async (descricao: string) => {
      setMontando(true);
      try {
        return await pedirAoCopiloto(token, descricao);
      } finally {
        setMontando(false);
      }
    },
    [token],
  );
  return { montando, pedir };
}
