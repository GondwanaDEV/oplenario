"use client";

// Hook de mutação — PATCH /api/legislativo/documentos/:id (Onda B Slice 6, "Salvar rascunho"). Mirror de
// use-salvar-rascunho-parecer.ts, com uma diferença de domínio: `id` é NULO até useGerarDocumento resolver
// (a página só monta o botão "Salvar rascunho" depois que o documento existe, mas o hook em si guarda
// contra a chamada prematura de qualquer jeito — defesa em profundidade, mesmo espírito do guard de token).

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { DocumentoOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

export type EditarDocumentoIn = { lockVersion: number; corpo?: string; assunto?: string };

type Estado = "ocioso" | "enviando" | "erro";

function paraKebab(chave: string): string {
  return chave.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

function corpoKebab(corpo: EditarDocumentoIn): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(corpo)
      .filter(([, v]) => v !== undefined)
      .map(([k, v]) => [paraKebab(k), v]),
  );
}

export function useEditarDocumento(token: string | null, id: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    // RE-ARMA no mount. Sem esta linha o ref nasce FALSE sob React StrictMode (dev), porque o
    // StrictMode roda cleanup+setup no primeiro mount — e entao TODO setEstado pos-resposta vira
    // no-op e nenhuma mensagem de erro do servidor chega na tela. Medido: 1,5s depois de um 409 o
    // botao seguia "Salvando..." com zero alerta na pagina. Os hooks de LEITURA ja faziam assim.
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
    }, []);

  async function editar(corpo: EditarDocumentoIn): Promise<DocumentoOut> {
    if (semCredencial(token)) {
      throw new Error("sem token de autenticacao");
    }
    if (!id) {
      throw new Error("documento ainda nao foi gerado");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch(`/api/legislativo/documentos/${encodeURIComponent(id)}`, {
        token: token ?? undefined,
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpoKebab(corpo)),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao salvar rascunho (status ${r.status})`;
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as DocumentoOut;
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
