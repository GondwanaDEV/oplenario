"use client";

// Onda B Slice 2 — PRIMEIRO hook de mutacao do app (nenhum POST/PATCH existia antes). Envia o corpo em
// kebab-case (o backend espera; nao ha' boundary de escrita ainda — o corpo e' escrito diretamente nas
// chaves que o wire/in espera, ja' que os campos aqui sao poucos e triviais de nomear a mao). Guard `vivo`
// contra unmount, mesmo idioma dos hooks de leitura (useProposicoes/useProposicaoDetalhe) — aqui via
// `useEffect` de cleanup dedicado, ja' que `criar` e' funcao imperativa (nao um efeito auto-disparado).

import { useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ProposicaoDetalheOut } from "./contrato-legislativo.gen";

export type CriarProposicaoIn = {
  tipo: string;
  ano: number;
  ementa: string;
  autorTipo?: string;
  autorId?: string;
  autorTexto?: string;
  objetoIndicacao?: string;
  destinatarioId?: string;
  destinatarioTexto?: string;
  tipoRequerimento?: string;
  categoriaMocao?: string;
  texto?: string;
};

type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function corpoKebab(corpo: CriarProposicaoIn): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo)
      .filter(([, v]) => v !== undefined)
      .map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useCriarProposicao(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function criar(corpo: CriarProposicaoIn): Promise<ProposicaoDetalheOut> {
    if (!token) {
      throw new Error("sem token de autenticacao");
    }
    setEstado("enviando");
    setErro(null);
    try {
      const r = await fetch("/api/legislativo/proposicoes", {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao protocolar (status ${r.status})`;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as ProposicaoDetalheOut;
      if (vivoRef.current) setEstado("ocioso");
      return dados;
    } catch (e) {
      if (vivoRef.current) setEstado("erro");
      throw e;
    }
  }

  return { criar, estado, erro };
}
