"use client";

// Hook de mutação — POST /api/sessoes/:id/votacoes/:votacaoId/meu-voto (Onda C3). Mirror de
// use-acusar-ciencia.ts. `vereador-id` nunca vem do cliente; o corpo só carrega `voto`.

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";

export type VotoNominalIn = "sim" | "nao" | "abstencao";
export type MeuVotoOut = { id: string };

type Estado = "ocioso" | "enviando" | "erro";

export function useMeuVoto(token: string | null) {
  const [estado, setEstado] = useState<Estado>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function votar(sessaoId: string, votacaoId: string, voto: VotoNominalIn): Promise<MeuVotoOut> {
    if (!token) {
      throw new Error("sem token de autenticacao");
    }
    if (enviandoRef.current) {
      throw new Error("envio em andamento");
    }
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false;
    try {
      const r = await apiFetch(`/api/sessoes/${sessaoId}/votacoes/${votacaoId}/meu-voto`, {
        token,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ voto }),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        // não repassa detalhe de POR QUE não pode votar (mesma disciplina do backend — 403 fail-closed
        // genérico não vaza qual precondição falhou); qualquer erro do servidor vira a mesma mensagem curta.
        const msg = r.status === 403 ? "Não é possível votar agora." : (corpoErro?.erro ?? `falha ao votar (status ${r.status})`);
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as MeuVotoOut;
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

  return { votar, estado, erro };
}
