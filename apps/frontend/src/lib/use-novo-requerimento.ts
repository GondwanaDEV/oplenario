"use client";

// Hook de IO da tela "Novo requerimento" do vereador (fatia 2a) — borda /meu (papel 'vereador'):
//   GET  /api/meu/modelos-requerimento   → os modelos da Casa + os campos que cada um pede
//   POST /api/meu/requerimentos/previa   → o texto EXATO que será assinado (autor e data do servidor)
//   POST /api/meu/requerimentos          → assina e protocola (201)
// Autor, data e texto final nunca saem daqui: o servidor resolve do login, do relógio e do modelo. Tipos
// GERADOS (contrato-legislativo.gen.ts). Os `campos` vão com as chaves do modelo INTACTAS (não passam pelo
// kebab/camel — são nomes de placeholder, não chaves de contrato).

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { ModeloRequerimentoOut, ModelosRequerimentoOut, RequerimentoProtocoladoOut } from "./contrato-legislativo.gen";
import { semCredencial } from "./modo";

type EstadoLeitura = "carregando" | "pronto" | "erro";
type EstadoEnvio = "ocioso" | "enviando" | "erro";

export type RascunhoRequerimento = { modeloId: string; campos: Record<string, string>; ementa: string };

async function erroDe(r: Response, padrao: string): Promise<string> {
  const corpo = await r.json().catch(() => null);
  return corpo?.erro ?? `${padrao} (status ${r.status})`;
}

export function useNovoRequerimento(token: string | null) {
  const [modelos, setModelos] = useState<ModeloRequerimentoOut[]>([]);
  const [estadoModelos, setEstadoModelos] = useState<EstadoLeitura>("carregando");
  const [estado, setEstado] = useState<EstadoEnvio>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    vivoRef.current = true; // re-arma sob StrictMode (mesmo racional de use-meu-emitir-parecer)
    return () => {
      vivoRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (semCredencial(token)) return; // sem credencial: o estado "erro" é derivado no render, abaixo
    let ativo = true;
    (async () => {
      try {
        const r = await apiFetch("/api/meu/modelos-requerimento", { token: token ?? undefined, cache: "no-store" });
        if (!ativo) return;
        if (!r.ok) {
          setEstadoModelos("erro");
          return;
        }
        const dados = camelizarChaves(await r.json()) as ModelosRequerimentoOut;
        if (!ativo) return;
        setModelos(dados.itens);
        setEstadoModelos("pronto");
      } catch {
        if (ativo) setEstadoModelos("erro");
      }
    })();
    return () => {
      ativo = false;
    };
  }, [token]);

  async function enviar<T>(url: string, corpo: Record<string, unknown>, padrao: string): Promise<T> {
    if (semCredencial(token)) throw new Error("sem token de autenticacao");
    if (enviandoRef.current) throw new Error("envio em andamento");
    enviandoRef.current = true;
    setEstado("enviando");
    setErro(null);
    let tratado = false; // erro HTTP já exposto com a mensagem do servidor
    try {
      const r = await apiFetch(url, {
        token: token ?? undefined,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpo),
      });
      if (!r.ok) {
        const msg = await erroDe(r, padrao);
        tratado = true;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        throw new Error(msg);
      }
      const dados = camelizarChaves(await r.json()) as T;
      if (vivoRef.current) setEstado("ocioso");
      return dados;
    } catch (e) {
      if (!tratado && vivoRef.current) {
        setEstado("erro");
        setErro("Falha de rede — tente novamente.");
      }
      throw e;
    } finally {
      enviandoRef.current = false;
    }
  }

  /** O texto formatado que será assinado. */
  async function previa(r: Omit<RascunhoRequerimento, "ementa">): Promise<string> {
    const { texto } = await enviar<{ texto: string }>(
      "/api/meu/requerimentos/previa",
      { "modelo-id": r.modeloId, campos: r.campos },
      "falha ao montar o texto",
    );
    return texto;
  }

  /** Assina e protocola. */
  function protocolar(r: RascunhoRequerimento): Promise<RequerimentoProtocoladoOut> {
    return enviar<RequerimentoProtocoladoOut>(
      "/api/meu/requerimentos",
      { "modelo-id": r.modeloId, campos: r.campos, ementa: r.ementa },
      "falha ao protocolar o requerimento",
    );
  }

  return {
    modelos,
    estadoModelos: semCredencial(token) ? ("erro" as EstadoLeitura) : estadoModelos,
    estado,
    erro,
    previa,
    protocolar,
  };
}
