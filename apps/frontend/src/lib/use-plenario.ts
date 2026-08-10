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
import { aplicarEvento, estadoInicial, falharQuorum, hidratarQuorum, type EstadoPlenario } from "./plenario-reducer";
import { consumirSse } from "./sse";

export type EstadoConexao = "carregando" | "ao-vivo" | "reconectando" | "erro";

/** Piso entre duas buscas de `/sessoes/:id/quorum`. A chamada nominal dispara ~1 evento por vereador em
 * poucos minutos; sem piso, cada um viraria um request da leitura MAIS CARA do módulo (resolve o roster
 * inteiro + presença + justificativas). Com 3s a rajada de uma Casa de 21 colapsa em poucas dezenas de
 * requests, e 3s de defasagem num painel de projetor é invisível. */
const REBUSCA_MIN_MS = 3000;

/** Rede de segurança contra a janela de replay do canal (retenção MINID de 5 min, `tempo_real/components`):
 * uma queda de stream mais longa que a retenção perde eventos em SILÊNCIO — o resume por Last-Event-ID pede
 * um id já aparado e o servidor não tem o que reenviar. Sem re-hidratação periódica, o telão exibiria "Ao
 * vivo" com um número errado pelo resto da sessão. */
const REBUSCA_PERIODICA_MS = 30000;

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

/** `comQuorum` liga a hidratação de `GET /sessoes/:id/quorum` — DESLIGADA por default, e isso é estrutural,
 * não economia. Este hook alimenta DUAS telas: o TELÃO (que precisa de "N de M") e o COCKPIT do vereador
 * (que só lê `estado.presentes` de forma nominal e nunca mostrou quórum). Manter o cockpit fora do caminho
 * de quórum (a) impede que qualquer evolução da hidratação volte a regredir a tela de votar, e (b) tira ~21
 * clientes por Casa do polling da leitura mais cara do módulo, deixando lá só os 1-2 telões. */
export function usePlenario(sessaoId: string, token: string | null, opcoes?: { comQuorum?: boolean }) {
  const comQuorum = opcoes?.comQuorum === true;
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
    let rebuscando = false;
    let ultimaRebusca = 0;

    /** Uma busca do snapshot de quórum. Best-effort e TOTAL: rede/403/500/parse deixam a tela no tri-estado
     * honesto (`falharQuorum`), nunca travam e nunca zeram um número já obtido. */
    const rehidratar = async () => {
      if (!comQuorum || !vivo || rebuscando) return;
      rebuscando = true;
      ultimaRebusca = Date.now();
      try {
        const resp = await apiFetch(`/api/sessoes/${sessaoId}/quorum`, {
          token: token ?? undefined,
          signal: controller.signal,
          cache: "no-store",
        });
        if (!vivo) return;
        if (!resp.ok) {
          setEstado((prev) => (prev ? falharQuorum(prev) : prev));
          return;
        }
        const q = camelizarChaves(await resp.json()) as QuorumSessaoOut;
        if (!vivo) return;
        // `hidratarQuorum` é TOTAL: um corpo de forma inesperada devolve o estado praticamente inalterado,
        // então este updater nunca lança — o que importa porque o React pode avaliá-lo na fase de RENDER.
        setEstado((prev) => (prev ? hidratarQuorum(prev, q) : prev));
      } catch {
        if (!vivo) return;
        setEstado((prev) => (prev ? falharQuorum(prev) : prev));
      } finally {
        rebuscando = false;
      }
    };

    // Pedido de re-busca do quórum, levantado pelo PRÓPRIO reducer (`precisaRehidratar`) — a regra de quais
    // eventos mexem no quórum mora num lugar só, não é redigitada aqui. Escrever `true` dentro do updater é
    // idempotente de propósito: o StrictMode pode invocá-lo duas vezes, e o efeito é o mesmo.
    let pedidoDeRebusca = false;

    const aoFrame = (f: { event?: string; data: string; id?: string }) => {
      if (!vivo) return;
      if (f.id) lastIdRef.current = f.id;
      if (!ehTipoPlenario(f.event)) return;
      try {
        const dados = JSON.parse(f.data);
        const evento = { tipo: f.event, seq: f.id ? Number(f.id) : 0, dados } as EventoPlenario;
        setEstado((prev) => {
          if (!prev) return prev;
          const proximo = aplicarEvento(prev, evento);
          if (proximo.precisaRehidratar) pedidoDeRebusca = true;
          return proximo;
        });
      } catch {
        // frame corrompido/forma inesperada: descarta (o servidor já valida na saída; defesa-em-profundidade)
      }
    };

    // Um único relógio governa as duas re-buscas, e nenhuma delas roda dentro de um updater de estado:
    //   - REATIVA (debounced): houve movimento de presença e já passou o piso -> re-busca. É o que faz o
    //     número do telão andar durante a chamada, coalescendo a rajada de 21 presenças.
    //   - PERIÓDICA: auto-cura. Cobre o buraco silencioso da retenção de 5 min do canal e qualquer evento
    //     perdido — sem ela, um erro vira permanente e a tela segue exibindo "Ao vivo" com confiança.
    const relogio = setInterval(() => {
      if (!vivo || !comQuorum) return;
      const desde = Date.now() - ultimaRebusca;
      if ((pedidoDeRebusca && desde >= REBUSCA_MIN_MS) || desde >= REBUSCA_PERIODICA_MS) {
        pedidoDeRebusca = false;
        void rehidratar();
      }
    }, 500);

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

      // 1b) hidratação do quórum — BEST-EFFORT e em paralelo ao SSE (não bloqueia a conexão ao vivo). O
      // numerador do telão vem SÓ daqui: nada de fundir o número do servidor com o delta do SSE (ver a
      // docstring de `hidratarQuorum`). Por isso ela não é um disparo único — é re-buscada sempre que a
      // presença se mexe (debounced), depois de toda reconexão e periodicamente.
      void rehidratar();

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
        // Toda reconexão re-hidrata: a retenção do canal é de 5 min e uma queda mais longa perde eventos em
        // SILÊNCIO (o resume pede um id já aparado). Sem isto, o badge voltaria a "Ao vivo" sobre um número
        // errado pelo resto da sessão. Custo: 1 request por queda.
        if (tentativa > 0) void rehidratar();
        try {
          await espera(Math.min(1000 * 2 ** tentativa, 15000), controller.signal);
        } catch {
          return; // abortado durante o backoff
        }
      }
    })();

    return () => {
      vivo = false;
      clearInterval(relogio);
      controller.abort();
    };
  }, [sessaoId, token, idValido, comQuorum]);

  // casos de erro derivados (mantêm o effect livre de setState síncrono)
  if (semCredencial(token)) return { sessao: null, estado: null, conexao: "erro" as EstadoConexao, erro: "Sem credencial de sessão (token)." };
  if (!idValido) return { sessao: null, estado: null, conexao: "erro" as EstadoConexao, erro: "Identificador de sessão inválido." };
  return { sessao, estado, conexao, erro };
}
