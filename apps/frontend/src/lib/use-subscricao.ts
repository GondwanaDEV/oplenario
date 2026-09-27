"use client";

// Hooks de IO do requerimento COLETIVO (fatia 2c) — borda /meu (papel 'vereador'):
//   GET  /api/meu/colegas                                 → quem pode ser convidado (mandato vigente, menos eu)
//   GET  /api/meu/subscricoes                             → pedidos de subscrição esperando a MINHA resposta
//   GET  /api/meu/requerimentos/propostas                 → as MINHAS propostas ainda esperando coautores
//   GET  /api/meu/requerimentos/propostas/:id             → a proposta (autor ou convidado; os demais: 404)
//   POST /api/meu/requerimentos/propostas/:id/resposta    → confirmar (assina) | recusar
//   POST /api/meu/requerimentos/propostas/:id/protocolo   → o autor assina e protocola
// Quem é quem o servidor resolve do login. Tipos GERADOS (contrato-legislativo.gen.ts).

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type {
  ColegaOut,
  ColegasOut,
  ConviteSubscricaoOut,
  ConvitesSubscricaoOut,
  PropostaRequerimentoOut,
  PropostaResumoOut,
  PropostasOut,
  RequerimentoColetivoProtocoladoOut,
} from "./contrato-legislativo.gen";

type EstadoLeitura = "carregando" | "pronto" | "erro";

async function ler<T>(token: string | null, url: string): Promise<T | null> {
  const r = await apiFetch(url, { token: token ?? undefined, cache: "no-store" });
  if (!r.ok) return null;
  return camelizarChaves(await r.json()) as T;
}

/** Leitura simples com 3 estados + recarregar (mesmo idioma de use-tempos-tribuna). */
function useLeitura<T>(token: string | null, url: string | null) {
  const [dados, setDados] = useState<T | null>(null);
  const [estado, setEstado] = useState<EstadoLeitura>("carregando");
  const vivoRef = useRef(true);

  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (!url || semCredencial(token)) return;
    let ativo = true;
    (async () => {
      try {
        const r = await ler<T>(token, url);
        if (!ativo) return;
        if (r === null) {
          setEstado("erro");
          return;
        }
        setDados(r);
        setEstado("pronto");
      } catch {
        if (ativo) setEstado("erro");
      }
    })();
    return () => {
      ativo = false;
    };
  }, [token, url]);

  const recarregar = useCallback(async () => {
    if (!url || semCredencial(token)) return;
    try {
      const r = await ler<T>(token, url);
      if (!vivoRef.current) return;
      if (r === null) setEstado("erro");
      else {
        setDados(r);
        setEstado("pronto");
      }
    } catch {
      if (vivoRef.current) setEstado("erro");
    }
  }, [token, url]);

  return { dados, estado: semCredencial(token) ? ("erro" as EstadoLeitura) : estado, recarregar };
}

export function useColegas(token: string | null): { colegas: ColegaOut[]; estado: EstadoLeitura } {
  const { dados, estado } = useLeitura<ColegasOut>(token, "/api/meu/colegas");
  return { colegas: dados?.itens ?? [], estado };
}

/** A home: convites esperando minha resposta + minhas propostas abertas. Erro de leitura some em silêncio
 * (a home não quebra por uma seção opcional) — as listas ficam vazias. */
export function useSubscricoesHome(token: string | null): {
  convites: ConviteSubscricaoOut[];
  propostas: PropostaResumoOut[];
} {
  const c = useLeitura<ConvitesSubscricaoOut>(token, "/api/meu/subscricoes");
  const p = useLeitura<PropostasOut>(token, "/api/meu/requerimentos/propostas");
  return { convites: c.dados?.itens ?? [], propostas: p.dados?.itens ?? [] };
}

export type ResultadoAcao<T> = { ok: true; dados: T } | { ok: false; erro: string };

export function usePropostaRequerimento(token: string | null, id: string) {
  const url = `/api/meu/requerimentos/propostas/${encodeURIComponent(id)}`;
  const { dados, estado, recarregar } = useLeitura<PropostaRequerimentoOut>(token, url);
  const [enviando, setEnviando] = useState(false);
  const enviandoRef = useRef(false);

  async function postar<T>(sufixo: string, corpo: Record<string, unknown> | null, padrao: string): Promise<ResultadoAcao<T>> {
    if (semCredencial(token)) return { ok: false, erro: "Sessão sem credencial — entre de novo." };
    if (enviandoRef.current) return { ok: false, erro: "Já estamos registrando." };
    enviandoRef.current = true;
    setEnviando(true);
    try {
      const r = await apiFetch(`${url}/${sufixo}`, {
        token: token ?? undefined,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: corpo ? JSON.stringify(corpo) : undefined,
      });
      if (!r.ok) {
        const c = await r.json().catch(() => null);
        // conflito = a situação mudou (alguém protocolou / já respondeu): a tela relê e mostra o estado real
        if (r.status === 409) await recarregar();
        return { ok: false, erro: c?.erro ?? `${padrao} (status ${r.status})` };
      }
      const d = camelizarChaves(await r.json()) as T;
      await recarregar();
      return { ok: true, dados: d };
    } catch {
      return { ok: false, erro: `${padrao}: falha de rede. Tente de novo.` };
    } finally {
      enviandoRef.current = false;
      setEnviando(false);
    }
  }

  return {
    proposta: dados,
    estado,
    enviando,
    responder: (acao: "confirmar" | "recusar") =>
      postar<{ estado: string }>("resposta", { acao }, "Não foi possível registrar a sua resposta"),
    protocolar: () =>
      postar<RequerimentoColetivoProtocoladoOut>("protocolo", null, "Não foi possível protocolar o requerimento"),
  };
}
