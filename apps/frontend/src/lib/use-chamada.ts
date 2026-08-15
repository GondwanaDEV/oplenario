"use client";

// Hook de IO da CHAMADA (§22.6 eixo C, Etapa 3B fatia 2) — busca o snapshot de `GET /sessoes/:id/chamada`
// (+ `GET /sessoes/:id/justificativas`), mantém os dois vivos via o canal SSE `/sessoes/:id/plenario`, e
// expõe as 3 escritas da tela (`marcarLinha`, `registrarChamada`, `decidirJustificativa`). Toda a lógica de
// ordenação/agrupamento/diff/otimista é de `chamada-vista.ts` (módulo puro, Etapa 3B fatia 1) — este hook só
// faz IO e nunca reimplementa nada de lá.
//
// A REGRA MAIS IMPORTANTE (herdada da Etapa 4, reprovada por violá-la — ver `plenario-reducer.ts`,
// `hidratarQuorum`): o estado/quórum da chamada vem SÓ do servidor, RE-BUSCADO. Um frame de SSE NUNCA muda
// `dados` por incremento — ele só PEDE uma re-busca de `GET /chamada`. IO é mirror de `use-plenario.ts` (o
// mesmo padrão já revisado): 3 gatilhos de re-hidratação (debounced pós-evento, pós-reconexão, periódico
// 30s) e um piso de 3s entre buscas — a rota resolve roster + presença + justificativas inteiras e não tem
// rate-limit no servidor ([GAP] de infra, carry da Etapa 4). O canal SSE é exposto como TRI-ESTADO honesto
// (`carregando` / `ao-vivo` / `reconectando`) — nunca afirma "ao vivo" sobre um dado que pode estar defasado.

import { useCallback, useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import {
  diffParaLote,
  type EstadoAlvo,
  type MarcacoesPendentes,
  type RegistroLote,
} from "./chamada-vista";
import { TIPOS_PLENARIO } from "./contrato";
import type {
  ChamadaConduzidaOut,
  ChamadaOut,
  JustificativaDecididaOut,
  JustificativasOut,
  LinhaJustificativaOut,
} from "./contrato-sessoes.gen";
import { semCredencial } from "./modo";
import { consumirSse } from "./sse";

export type EstadoDados = "carregando" | "pronto" | "erro";
export type EstadoCanal = "carregando" | "ao-vivo" | "reconectando";

export type ResultadoRegistrarChamada = { ok: true; ato: ChamadaConduzidaOut } | { ok: false; erro: string };
export type ResultadoDecisao =
  | { ok: true; decisao: JustificativaDecididaOut }
  | { ok: false; erro: string; conflito: boolean };

/** Piso entre duas buscas de `GET /sessoes/:id/chamada`. Espelha `REBUSCA_MIN_MS` de `use-plenario.ts` (mesmo
 * motivo, mesma constante) — a rota resolve roster + presença + justificativas inteiras e o servidor não a
 * protege com rate-limit ([GAP] de infra, carry da Etapa 4). Sem piso, a rajada de uma chamada nominal (~1
 * evento por vereador) viraria uma rajada de requests à leitura mais cara do módulo. */
const REBUSCA_MIN_MS = 3000;

/** Rede de segurança contra a janela de replay do canal (retenção MINID de 5 min, `tempo_real/components`):
 * uma queda de stream mais longa que a retenção perde eventos em SILÊNCIO. Sem re-hidratação periódica, o
 * badge voltaria a "Ao vivo" exibindo um snapshot desatualizado pelo resto da sessão. */
const REBUSCA_PERIODICA_MS = 30000;

const ID_VALIDO = /^[a-zA-Z0-9_-]{1,128}$/;

const ehTipoPlenario = (t?: string): boolean => !!t && (TIPOS_PLENARIO as readonly string[]).includes(t);

/** setTimeout abort-aware: rejeita ao abortar p/ não segurar a IIFE até 15s após o unmount (mesmo racional de
 * `use-plenario.ts`). */
const espera = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    if (signal.aborted) return reject(signal.reason);
    const id = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => { clearTimeout(id); reject(signal.reason); }, { once: true });
  });

function registroKebab(r: RegistroLote): Record<string, unknown> {
  return { "vereador-id": r.vereadorId, tipo: r.tipo, modalidade: r.modalidade, "ocorrido-em": r.ocorridoEm };
}

/** "2026-07-19T14:32:00Z" -> "14h32" (fuso do NAVEGADOR — a hora é só para leitura humana num painel/console
 * que roda na mesma máquina/fuso da Casa; nunca usada para comparação, então não há [GAP] de timezone aqui). */
function horaPtBr(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  return `${hh}h${mm}`;
}

/** Traduz o `{erro}` cru do 409 das rotas de presença/chamada no par CAUSA+CORREÇÃO que o design fixou —
 * nunca "erro ao salvar". Os dois padrões casados aqui são os ÚNICOS textos ESTÁVEIS que
 * `logic/mensagem-de-recusa-de-presenca` e o 409 de `:conflito/sessao-sem-data` produzem nas 3 rotas de
 * escrita da chamada (presença, lote, ato). Qualquer outro 409 repassa o texto do domínio cru — ele já é
 * acionável (as outras mensagens de `mensagem-de-recusa-de-presenca` dizem o limite violado) — nunca um
 * genérico "erro ao salvar".
 *
 * CARRY: o 409 de sessão encerrada não carrega `encerrada-em` no corpo (só o NOME do estado) — só dá para
 * cravar "às HHhMM" quando o CHAMADOR passa `sessaoEncerradaEm` (a tela tipicamente já tem `SessaoOut`
 * carregado por outro hook da mesma página). Sem isso, a frase sai sem a hora — nunca uma hora inventada. */
export function mensagemDeErro(
  corpoErro: string | null | undefined,
  status: number,
  sessaoEncerradaEm?: string | null,
): string {
  const erro = corpoErro ?? "";
  if (/sessao sem data marcada/.test(erro)) {
    return "Esta sessão não tem data nem hora de abertura. Sem data não há composição da Casa a consultar — informe a data da sessão para fazer a chamada.";
  }
  if (/nao aceita mais registro de presenca/.test(erro)) {
    const hora = sessaoEncerradaEm ? ` às ${horaPtBr(sessaoEncerradaEm)}` : "";
    return `A sessão foi encerrada${hora}. A presença desta sessão está fechada e não pode mais ser alterada — a correção de um registro errado se faz pela ata.`;
  }
  return erro || `falha ao salvar (status ${status})`;
}

export interface UseChamadaOpcoes {
  /** `SessaoOut.encerradaEm`, quando o chamador já o tem carregado — formata a hora na mensagem de 409 de
   * sessão encerrada. Este hook não busca `SessaoOut` (fora do seu escopo de IO); sem o valor, a mensagem
   * sai sem "às HHhMM" em vez de inventar uma hora. */
  sessaoEncerradaEm?: string | null;
}

export function useChamada(sessaoId: string, token: string | null, opcoes?: UseChamadaOpcoes) {
  const [dados, setDados] = useState<ChamadaOut | null>(null);
  const [justificativas, setJustificativas] = useState<LinhaJustificativaOut[] | null>(null);
  const [estado, setEstado] = useState<EstadoDados>("carregando");
  const [canal, setCanal] = useState<EstadoCanal>("carregando");
  const [erro, setErro] = useState<string | null>(null);
  const idValido = ID_VALIDO.test(sessaoId);
  const sessaoEncerradaEm = opcoes?.sessaoEncerradaEm ?? null;

  const dadosRef = useRef<ChamadaOut | null>(null);
  useEffect(() => {
    dadosRef.current = dados;
  }, [dados]);

  // guarda de todo o ciclo de vida do hook (write callbacks vivem fora do effect de IO abaixo)
  const vivoRef = useRef(true);
  useEffect(() => {
    vivoRef.current = true;
    return () => {
      vivoRef.current = false;
    };
  }, []);

  /** Busca PURA — lança em não-ok, sem tocar estado. Usada pela carga inicial (que trata erro à parte) e
   * pela re-hidratação (best-effort). `signal` opcional: os gatilhos internos do canal passam o `controller`
   * do effect; a `recarregar()` pública (ação explícita do operador) não amarra a um controller de IO. */
  const buscarChamada = useCallback(
    async (signal?: AbortSignal): Promise<ChamadaOut> => {
      const r = await apiFetch(`/api/sessoes/${sessaoId}/chamada`, { token: token ?? undefined, signal, cache: "no-store" });
      if (!r.ok) throw new Error(`chamada ${r.status}`);
      return camelizarChaves(await r.json()) as ChamadaOut;
    },
    [sessaoId, token],
  );

  const buscarJustificativas = useCallback(
    async (signal?: AbortSignal): Promise<LinhaJustificativaOut[] | null> => {
      const r = await apiFetch(`/api/sessoes/${sessaoId}/justificativas`, { token: token ?? undefined, signal, cache: "no-store" });
      if (!r.ok) return null; // best-effort: a lista de justificativas não trava a chamada
      const j = camelizarChaves(await r.json()) as JustificativasOut;
      return j.justificativas;
    },
    [sessaoId, token],
  );

  /** Recarga explícita (botão "Atualizar", ou pós-escrita bem-sucedida). Nunca zera um snapshot bom em
   * falha — o mesmo espírito degradado de `falharQuorum`. */
  const recarregar = useCallback(async () => {
    if (semCredencial(token) || !idValido) return;
    try {
      const c = await buscarChamada();
      if (vivoRef.current) {
        setDados(c);
        setEstado("pronto");
      }
    } catch {
      // mantém o último snapshot na tela — recarregar explícito não zera
    }
    const j = await buscarJustificativas().catch(() => null);
    if (j && vivoRef.current) setJustificativas(j);
  }, [token, idValido, buscarChamada, buscarJustificativas]);

  useEffect(() => {
    if (semCredencial(token) || !idValido) return; // caso de erro derivado no retorno (sem setState síncrono no effect)
    const controller = new AbortController();
    let vivo = true;
    let rebuscando = false;
    let ultimaRebusca = 0;
    let pedidoDeRebusca = false;

    /** Re-hidratação best-effort: falha aqui NUNCA apaga o último snapshot bom (degrada, não zera). */
    const rehidratar = async () => {
      if (!vivo || rebuscando) return;
      rebuscando = true;
      ultimaRebusca = Date.now();
      try {
        const c = await buscarChamada(controller.signal);
        if (vivo) setDados(c);
      } catch {
        // best-effort — ver docstring
      } finally {
        rebuscando = false;
      }
    };

    const aoFrame = (f: { event?: string; data: string; id?: string }) => {
      if (!vivo) return;
      // NUNCA aplica o frame ao estado — só PEDE re-busca (a regra que a Etapa 4 reprovou por violar).
      if (ehTipoPlenario(f.event)) pedidoDeRebusca = true;
    };

    // Um único relógio governa as duas re-buscas automáticas (nenhuma roda dentro de um updater de estado):
    //   - REATIVA (debounced): um evento de presença chegou e já passou o piso -> re-busca (coalesce a
    //     rajada da chamada nominal num punhado de requests).
    //   - PERIÓDICA: auto-cura da retenção de 5 min do canal (ver a constante acima).
    const relogio = setInterval(() => {
      if (!vivo) return;
      const desde = Date.now() - ultimaRebusca;
      if ((pedidoDeRebusca && desde >= REBUSCA_MIN_MS) || desde >= REBUSCA_PERIODICA_MS) {
        pedidoDeRebusca = false;
        void rehidratar();
      }
    }, 500);

    (async () => {
      // 1) carga inicial
      try {
        const c = await buscarChamada(controller.signal);
        if (!vivo) return;
        setDados(c);
        setEstado("pronto");
        ultimaRebusca = Date.now(); // o relógio periódico conta a partir daqui, não de "época zero"
      } catch {
        if (!vivo || controller.signal.aborted) return;
        setEstado("erro");
        setErro("Não foi possível carregar a chamada.");
        return;
      }
      void buscarJustificativas(controller.signal).then((j) => {
        if (vivo && j) setJustificativas(j);
      });

      // 2) canal SSE com reconexão por backoff — só sinaliza rebusca, nunca aplica o frame ao estado
      let tentativa = 0;
      while (vivo) {
        try {
          setCanal("ao-vivo");
          await consumirSse(`/api/sessoes/${sessaoId}/plenario`, { token, signal: controller.signal, aoFrame });
          if (!vivo) return;
          tentativa = 0; // stream fechou LIMPO -> resume rápido
        } catch {
          if (!vivo || controller.signal.aborted) return;
          setCanal("reconectando");
          tentativa += 1;
        }
        // Toda reconexão re-hidrata: a retenção do canal é de 5 min e uma queda mais longa perde eventos em
        // SILÊNCIO. Sem isto, o badge voltaria a "Ao vivo" sobre um snapshot desatualizado pelo resto da sessão.
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
  }, [sessaoId, token, idValido, buscarChamada, buscarJustificativas]);

  /** Marca UMA linha "ao vivo" (grava na hora, fora do fluxo de lote): POST /presenca com feedback OTIMISTA
   * — a linha muda na tela antes da resposta do servidor — e ROLLBACK exato em falha. Reusa `diffParaLote`
   * (com um único vereador na marcação) para derivar `tipo`/`modalidade` — a MESMA aritmética de transição
   * do lote, nunca uma segunda. */
  const marcarLinha = useCallback(
    async (vereadorId: string, estadoAlvo: EstadoAlvo): Promise<void> => {
      if (semCredencial(token)) throw new Error("sem token de autenticacao");
      const atual = dadosRef.current;
      if (!atual) throw new Error("chamada ainda nao foi carregada");

      const desde = new Date().toISOString();
      const diff = diffParaLote(atual.linhas, { [vereadorId]: { estadoAlvo, desde } });
      if (!diff.ok) throw new Error(diff.erro);
      if (diff.registros.length === 0) return; // nada mudou (mesmo estado, ou linha não editável) — no-op

      const registro = diff.registros[0];
      const linhasAntes = atual.linhas; // snapshot exato p/ reverter — nunca um valor fabricado
      const linhasOtimistas = atual.linhas.map((l) =>
        l.vereadorId === vereadorId ? { ...l, estado: estadoAlvo, desde: registro.ocorridoEm } : l,
      );
      if (vivoRef.current) setDados({ ...atual, linhas: linhasOtimistas });

      try {
        const r = await apiFetch(`/api/sessoes/${sessaoId}/presenca`, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(registroKebab(registro)),
        });
        if (!r.ok) {
          const corpoErro = await r.json().catch(() => null);
          throw new Error(mensagemDeErro(corpoErro?.erro, r.status, sessaoEncerradaEm));
        }
      } catch (e) {
        // ROLLBACK: restaura o snapshot EXATO anterior — mesmo espírito de `reverterOtimista`, aqui aplicado
        // a uma linha só (fora do mapa de marcações pendentes, que é do fluxo de lote).
        if (vivoRef.current) setDados((prev) => (prev ? { ...prev, linhas: linhasAntes } : prev));
        throw e;
      }
    },
    [sessaoId, token, sessaoEncerradaEm],
  );

  /** A chamada em LOTE: `diffParaLote` (módulo puro) -> `POST /presenca/lote` -> `POST /chamada` (o ato) ->
   * re-busca. Se o lote falhar, o ato NÃO acontece — um lote que falhou parcialmente deixaria a Mesa
   * achando que a chamada foi conduzida sobre presença que o servidor nunca confirmou por inteiro. Lote
   * VAZIO (todas as marcações já refletem o estado atual) pula o POST de lote — o servidor recusa um
   * envelope de 0 registros — e vai direto ao ato, que continua um gesto válido ("conferi e está correto"). */
  const registrarChamada = useCallback(
    async (marcacoes: MarcacoesPendentes): Promise<ResultadoRegistrarChamada> => {
      if (semCredencial(token)) return { ok: false, erro: "sem token de autenticacao" };
      const atual = dadosRef.current;
      if (!atual) return { ok: false, erro: "chamada ainda nao foi carregada" };

      const diff = diffParaLote(atual.linhas, marcacoes);
      if (!diff.ok) return { ok: false, erro: diff.erro };

      if (diff.registros.length > 0) {
        const r = await apiFetch(`/api/sessoes/${sessaoId}/presenca/lote`, {
          token: token ?? undefined,
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ registros: diff.registros.map(registroKebab) }),
        });
        if (!r.ok) {
          const corpoErro = await r.json().catch(() => null);
          return { ok: false, erro: mensagemDeErro(corpoErro?.erro, r.status, sessaoEncerradaEm) };
        }
      }

      const ra = await apiFetch(`/api/sessoes/${sessaoId}/chamada`, {
        token: token ?? undefined,
        method: "POST",
        headers: { "Content-Type": "application/json" },
      });
      if (!ra.ok) {
        const corpoErro = await ra.json().catch(() => null);
        await recarregar(); // o lote JÁ aconteceu — reflete o que o servidor de fato gravou, mesmo com o ato recusado
        return { ok: false, erro: mensagemDeErro(corpoErro?.erro, ra.status, sessaoEncerradaEm) };
      }
      const ato = camelizarChaves(await ra.json()) as ChamadaConduzidaOut;
      await recarregar();
      return { ok: true, ato };
    },
    [sessaoId, token, sessaoEncerradaEm, recarregar],
  );

  /** PATCH /justificativas/:jid/decisao. 409 nesta rota é SEMPRE conflito de concorrência — o CAS de
   * `lock-version` é o único motivo de recusa aqui (não há gate de janela como em presença): outra pessoa
   * decidiu esta justificativa entre a tela carregar e este clique. */
  const decidirJustificativa = useCallback(
    async (jid: string, decisao: "aprovada" | "indeferida", lockVersion: number): Promise<ResultadoDecisao> => {
      if (semCredencial(token)) return { ok: false, conflito: false, erro: "sem token de autenticacao" };
      const r = await apiFetch(`/api/sessoes/${sessaoId}/justificativas/${encodeURIComponent(jid)}/decisao`, {
        token: token ?? undefined,
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ estado: decisao, "lock-version": lockVersion }),
      });
      if (!r.ok) {
        if (r.status === 409) {
          return {
            ok: false,
            conflito: true,
            erro:
              "Esta justificativa foi decidida por outra pessoa enquanto você a analisava — a versão que você tinha na tela ficou desatualizada. Recarregue a lista de justificativas e confira a decisão atual antes de tentar de novo.",
          };
        }
        const corpoErro = await r.json().catch(() => null);
        return { ok: false, conflito: false, erro: corpoErro?.erro ?? `falha ao decidir justificativa (status ${r.status})` };
      }
      const decisaoOut = camelizarChaves(await r.json()) as JustificativaDecididaOut;
      await recarregar(); // a decisão muda o estado DERIVADO da linha na chamada (ausente-justificado etc.)
      return { ok: true, decisao: decisaoOut };
    },
    [sessaoId, token, recarregar],
  );

  // casos de erro derivados (mantêm o effect livre de setState síncrono)
  if (semCredencial(token)) {
    return {
      dados: null, justificativas: null, estado: "erro" as EstadoDados, canal: "carregando" as EstadoCanal,
      erro: "Sem credencial de sessão (token).", recarregar, marcarLinha, registrarChamada, decidirJustificativa,
    };
  }
  if (!idValido) {
    return {
      dados: null, justificativas: null, estado: "erro" as EstadoDados, canal: "carregando" as EstadoCanal,
      erro: "Identificador de sessão inválido.", recarregar, marcarLinha, registrarChamada, decidirJustificativa,
    };
  }
  return { dados, justificativas, estado, canal, erro, recarregar, marcarLinha, registrarChamada, decidirJustificativa };
}
