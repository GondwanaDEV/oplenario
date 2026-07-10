"use client";

// Onda B Slice 2 — segundo hook de mutacao (irmao de use-criar-proposicao). `id` e' fixo por instancia
// (a pagina de edicao ja sabe qual proposicao esta' editando). Mesmo guard de unmount via `useEffect` de
// cleanup dedicado (ver use-criar-proposicao.ts).

import { useEffect, useRef, useState } from "react";
import { camelizarChaves } from "./boundary";
import type { ProposicaoDetalheOut } from "./contrato-legislativo.gen";

export type EditarProposicaoIn = {
  lockVersion: number;
  ementa?: string;
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

function corpoKebab(corpo: EditarProposicaoIn): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo)
      .filter(([, v]) => v !== undefined)
      .map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useEditarProposicao(token: string | null, id: string) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  // Guard de re-entrancia via ref (nao via `estado`, capturado stale no closure): evita duplo-submit
  // sincrono (ex.: Enter repetido), defesa independente do botao desabilitado na UI.
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function editar(corpo: EditarProposicaoIn): Promise<ProposicaoDetalheOut> {
    if (!token) {
      throw new Error("sem token de autenticacao");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    // `tratado` distingue "erro do backend ja' exibido" de "falha de rede crua": sem ele, o catch
    // sobrescreveria a mensagem especifica (ex.: "conflito", "invalido") com a generica de rede.
    let tratado = false;
    try {
      const r = await fetch(`/api/legislativo/proposicoes/${id}`, {
        method: "PATCH",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao salvar (status ${r.status})`;
        tratado = true;
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
      if (vivoRef.current && !tratado) {
        setEstado("erro");
        setErro("falha de rede — tente novamente");
      }
      throw e;
    } finally {
      enviandoRef.current = false;
    }
  }

  return { editar, estado, erro };
}
