"use client";

// Hook de mutação — POST /api/sessoes (agendar sessão). Fecha o GAP docs/20: a criação de sessão só existia
// via API. Corpo kebab {sessao-legislativa-id, tipo-sessao, modalidade?, agendada-para?}; devolve o
// SessaoOut criado (com id) para a tela linkar direto ao comando da sessão. Papel `secretario` no backend.
// Mirror do idioma de `use-meu-voto.ts` (POST simples + estado + erro).

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type { SessaoOut } from "./contrato-sessoes.gen";

export type EstadoAgendar = "ocioso" | "enviando" | "erro";

export interface AgendarArgs {
  sessaoLegislativaId: string;
  tipoSessao: string;
  modalidade?: string | null;
  agendadaPara?: string | null; // ISO-8601 ou null
}

export type ResultadoAgendar = { ok: true; sessao: SessaoOut } | { ok: false; erro: string };

export function useAgendarSessao(token: string | null) {
  const [estado, setEstado] = useState<EstadoAgendar>("ocioso");
  const [erro, setErro] = useState<string | null>(null);
  const vivoRef = useRef(true);
  const enviandoRef = useRef(false);

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  async function agendar(args: AgendarArgs): Promise<ResultadoAgendar> {
    if (semCredencial(token)) return { ok: false, erro: "sem token de autenticacao" };
    if (enviandoRef.current) return { ok: false, erro: "envio em andamento" };
    enviandoRef.current = true;
    if (vivoRef.current) {
      setEstado("enviando");
      setErro(null);
    }
    const corpo: Record<string, unknown> = {
      "sessao-legislativa-id": args.sessaoLegislativaId,
      "tipo-sessao": args.tipoSessao,
    };
    if (args.modalidade) corpo.modalidade = args.modalidade;
    if (args.agendadaPara) corpo["agendada-para"] = args.agendadaPara;

    try {
      const r = await apiFetch("/api/sessoes", {
        token: token ?? undefined,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(corpo),
      });
      if (!r.ok) {
        const corpoErro = await r.json().catch(() => null);
        const msg = corpoErro?.erro ?? `falha ao agendar a sessão (status ${r.status})`;
        if (vivoRef.current) {
          setEstado("erro");
          setErro(msg);
        }
        return { ok: false, erro: msg };
      }
      const sessao = camelizarChaves(await r.json()) as SessaoOut;
      if (vivoRef.current) setEstado("ocioso");
      return { ok: true, sessao };
    } catch {
      if (vivoRef.current) {
        setEstado("erro");
        setErro("Falha de rede — tente novamente.");
      }
      return { ok: false, erro: "Falha de rede — tente novamente." };
    } finally {
      enviandoRef.current = false;
    }
  }

  return { agendar, estado, erro };
}
