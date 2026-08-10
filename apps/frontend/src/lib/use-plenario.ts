"use client";

// Hook do painel ao vivo: busca o estado inicial (GET /api/sessoes/:id), abre o SSE autenticado
// (/api/sessoes/:id/plenario) e dobra cada evento pelo reducer puro. Reconecta com backoff resumindo
// pelo Last-Event-ID. Todo o IO mora aqui; a lógica de estado é o reducer testado (plenario-reducer).

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { EventoPlenario, SessaoOut } from "./contrato";
import { TIPOS_PLENARIO } from "./contrato";
import type { QuorumSessaoOut } from "./contrato-sessoes.gen";
import { semCredencial } from "./modo";
import { aplicarEvento, estadoInicial, hidratarQuorum, type EstadoPlenario } from "./plenario-reducer";
import { consumirSse } from "./sse";

export type EstadoConexao = "carregando" | "ao-vivo" | "reconectando" | "erro";

const ehTipoPlenario = (t?: string): t is EventoPlenario["tipo"] =>
  !!t && (TIPOS_PLENARIO as readonly string[]).includes(t);

// id de sessão que viaja na URL: aceita só o formato esperado antes de ir à rede (review seg MINOR-2).
const ID_VALIDO = /^[a-zA-Z0-9_-]{1,128}$/;

/** setTimeout abort-aware: rejeita ao abortar p/ não segurar a IIFE até 15s após o unmount (review react MEDIUM). */
const espera = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    if (signal.aborted) return reject(signal.reason);
    const id = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => { clearTimeout(id); reject(signal.reason); }, { once: true });
  });

export function usePlenario(sessaoId: string, token: string | null) {
  const [sessao, setSessao] = useState<SessaoOut | null>(null);
  const [estado, setEstado] = useState<EstadoPlenario | null>(null);
  const [conexao, setConexao] = useState<EstadoConexao>("carregando");
  const [erro, setErro] = useState<string | null>(null);
  const lastIdRef = useRef<string | undefined>(undefined);
  const idValido = ID_VALIDO.test(sessaoId);

  useEffect(() => {
    if (semCredencial(token) || !idValido) return; // casos de erro são derivados no retorno (sem setState síncrono no effect)
    const controller = new AbortController();
    let vivo = true;

    const aoFrame = (f: { event?: string; data: string; id?: string }) => {
      if (!vivo) return;
      if (f.id) lastIdRef.current = f.id;
      if (!ehTipoPlenario(f.event)) return;
      try {
        const dados = JSON.parse(f.data);
        const evento = { tipo: f.event, seq: f.id ? Number(f.id) : 0, dados } as EventoPlenario;
        setEstado((prev) => (prev ? aplicarEvento(prev, evento) : prev));
      } catch {
        // frame corrompido/forma inesperada: descarta (o servidor já valida na saída; defesa-em-profundidade)
      }
    };

    (async () => {
      // 1) estado inicial
      try {
        const resp = await apiFetch(`/api/sessoes/${sessaoId}`, {
          token: token ?? undefined,
          signal: controller.signal,
          cache: "no-store",
        });
        if (!resp.ok) throw new Error(`sessao ${resp.status}`);
        const s = (await resp.json()) as SessaoOut;
        if (!vivo) return;
        setSessao(s);
        setEstado(estadoInicial(s));
      } catch (e) {
        if (!vivo || controller.signal.aborted) return;
        setConexao("erro");
        // só repassa mensagem controlada (status); erro de rede cru vaza topologia (review seg MINOR-4)
        setErro(e instanceof Error && /^sessao \d+$/.test(e.message) ? "Sessão indisponível." : "Não foi possível carregar a sessão.");
        return;
      }

      // 1b) hidratação do quórum (Etapa 4b, GET /sessoes/:id/quorum) — BEST-EFFORT, dispara em paralelo ao
      // SSE (não bloqueia a conexão ao vivo) e NUNCA quebra a tela: rede/403/500 aqui deixam o denominador
      // nulo e o numerador segue funcionando só pelos eventos SSE (mesmo comportamento de antes da Etapa
      // 4b). `hidratarQuorum` é PURA e já reconcilia sozinha a corrida com os eventos que chegarem antes/depois.
      apiFetch(`/api/sessoes/${sessaoId}/quorum`, { token: token ?? undefined, signal: controller.signal, cache: "no-store" })
        .then(async (resp) => {
          if (!vivo || !resp.ok) return;
          const q = camelizarChaves(await resp.json()) as QuorumSessaoOut;
          if (!vivo) return;
          setEstado((prev) => (prev ? hidratarQuorum(prev, q) : prev));
        })
        .catch(() => {
          // falha de rede/parse: silenciosa de propósito — não é um erro de PÁGINA (a sessão já carregou),
          // é um dado a menos que o telão exibe como "denominador ausente" em vez de travar.
        });

      // 2) stream com reconexão por backoff (resume via Last-Event-ID)
      let tentativa = 0;
      while (vivo) {
        try {
          setConexao("ao-vivo");
          await consumirSse(`/api/sessoes/${sessaoId}/plenario`, {
            token,
            signal: controller.signal,
            lastEventId: lastIdRef.current,
            aoFrame,
          });
          if (!vivo) return;
          tentativa = 0; // stream fechou LIMPO -> resume rápido (review react HIGH: reset só no sucesso)
        } catch (e) {
          if (!vivo || controller.signal.aborted) return;
          if (e instanceof Error && /SSE 40[13]/.test(e.message)) {
            setConexao("erro"); // 401/403 não se resolve com retry — decide ANTES de "reconectando" (review react MEDIUM)
            setErro("Acesso ao painel negado.");
            return;
          }
          setConexao("reconectando");
          tentativa += 1;
        }
        try {
          await espera(Math.min(1000 * 2 ** tentativa, 15000), controller.signal);
        } catch {
          return; // abortado durante o backoff
        }
      }
    })();

    return () => {
      vivo = false;
      controller.abort();
    };
  }, [sessaoId, token, idValido]);

  // casos de erro derivados (mantêm o effect livre de setState síncrono)
  if (semCredencial(token)) return { sessao: null, estado: null, conexao: "erro" as EstadoConexao, erro: "Sem credencial de sessão (token)." };
  if (!idValido) return { sessao: null, estado: null, conexao: "erro" as EstadoConexao, erro: "Identificador de sessão inválido." };
  return { sessao, estado, conexao, erro };
}
