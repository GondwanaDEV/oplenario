"use client";

// Hook do painel ao vivo: busca o estado inicial (GET /api/sessoes/:id), abre o SSE autenticado
// (/api/sessoes/:id/plenario) e dobra cada evento pelo reducer puro. Reconecta com backoff resumindo
// pelo Last-Event-ID. Todo o IO mora aqui; a lógica de estado é o reducer testado (plenario-reducer).

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import type { EventoPlenario, SessaoOut } from "./contrato";
import { TIPOS_PLENARIO } from "./contrato";
import type { ComposicaoSessaoOut, QuorumSessaoOut, TribunaOut } from "./contrato-sessoes.gen";
import { semCredencial } from "./modo";
import { aplicarEvento, estadoInicial, falharComposicao, falharQuorum, falharTribuna, falharVotacao, hidratarComposicao, hidratarQuorum, hidratarTribuna, hidratarVotacao, type EstadoPlenario, type TribunaEventoSeqNoDisparo, type VotacaoAbertaSnapshot } from "./plenario-reducer";
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

/** Fix round 1 (I3): `apiFetch` não tem timeout próprio, e o único `AbortSignal` daqui era o de
 * unmount — uma `/quorum` ou `/tribuna` pendurada (worker travado, TCP meio-aberta atrás de proxy,
 * exatamente o ambiente da queda longa do momento 3) nunca resolvia, nunca rejeitava, e travava a
 * re-hidratação daquela rota PARA SEMPRE enquanto o badge seguia dizendo "Ao vivo". Não há convenção
 * de timeout em `apiFetch` nem nos vizinhos (conferido) — este é o primeiro ponto a precisar de um, daí
 * o abort local em vez de mexer no boundary compartilhado. 8s: bem acima de qualquer latência real de
 * rede (a leitura mais cara do módulo ainda responde em baixas centenas de ms), bem abaixo da
 * periódica de 30s (então um travamento não consome o ciclo inteiro sem tentar de novo) e abaixo do
 * teto de backoff de 15s (uma reconexão do momento 3 não fica presa atrás de um timeout mais longo que
 * ela mesma).
 *
 * Fix round 2 (A3): decisão explícita sobre rede lenta PERSISTENTE (RTT > 8s de forma sustentada, não
 * uma queda pontual): a cadência vira 1 tentativa a cada `REBUSCA_PERIODICA_MS`, cada uma abortada aos
 * 8s — nunca completa enquanto a rede seguir lenta, e o número exibido fica congelado (degrada, não
 * zera — `falharQuorum`/`falharTribuna`). Decisão: MANTER sem backoff nem sinalização extra. Um telão
 * de plenário numa LAN interna com RTT sustentado > 8s já está num regime patológico por si só (a
 * leitura mais cara do módulo responde em baixas centenas de ms em operação normal); a alternativa
 * (backoff progressivo, ou alargar o timeout) trocaria "número velho, badge ao vivo" por "número velho
 * por mais tempo ainda" sem resolver a causa. Testado em `use-plenario.test.ts` ("A3"). Exportada para o
 * teste não redigitar o valor (a mesma razão de não redigitar `TIPOS_EVENTO_FALA`/`TIPOS_EVENTO_INSCRICAO`
 * em outro lugar). */
export const TIMEOUT_REBUSCA_MS = 8000;

/** Compõe um `AbortSignal` com timeout SEM `AbortSignal.any`/`AbortSignal.timeout`. Fix round 2 (A2):
 * as duas APIs são baseline recente (Chrome 116 / Safari 17.4) e este projeto não tem `browserslist`. O
 * telão de uma câmara municipal É a máquina que roda navegador velho — TV/quiosque fixo, ligado por
 * horas, que ninguém atualiza. Num navegador sem a API, a chamada lançaria DENTRO do `try` de
 * `buscarQuorum`/`buscarTribuna`, caindo no `catch` igual a qualquer outra falha de rede — MAS antes de
 * qualquer fetch sair, então a tribuna NUNCA hidrataria, em SILÊNCIO: sem erro visível, sem log, sem
 * sinal — exatamente o defeito que esta frente existe para matar, reintroduzido pela própria correção.
 * `AbortController` + `setTimeout` + listener no sinal-pai é suportado universalmente.
 *
 * `limpar()` DEVE ser chamado no caminho feliz (dentro do `finally` de quem chama) — sem isso o timer
 * só é liberado quando dispara ou quando o sinal-pai aborta, vazando um `setTimeout` (e o listener no
 * sinal-pai) por chamada até lá. */
function sinalComTimeout(sinalPai: AbortSignal, ms: number): { signal: AbortSignal; limpar: () => void } {
  const composto = new AbortController();
  const propagarAbort = () => composto.abort(sinalPai.reason);
  if (sinalPai.aborted) {
    propagarAbort();
  } else {
    sinalPai.addEventListener("abort", propagarAbort, { once: true });
  }
  const timer = setTimeout(() => composto.abort(new DOMException("Tempo de resposta esgotado", "TimeoutError")), ms);
  const limpar = () => {
    clearTimeout(timer);
    sinalPai.removeEventListener("abort", propagarAbort);
  };
  return { signal: composto.signal, limpar };
}

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

/** `comQuorum` seleciona o TELÃO — liga a hidratação de `GET /sessoes/:id/quorum` E de
 * `GET /sessoes/:id/tribuna` — e vem DESLIGADA por default, o que é estrutural, não economia. Este hook
 * alimenta DUAS telas: o TELÃO (que precisa de "N de M" e de quem está com a palavra) e o COCKPIT do
 * vereador (que só lê `estado.presentes` de forma nominal e nunca mostrou quórum nem tribuna). Manter o
 * cockpit fora deste caminho (a) impede que qualquer evolução da hidratação volte a regredir a tela de
 * votar, e (b) tira ~21 clientes por Casa do polling das leituras mais caras do módulo, deixando-o só
 * para os 1-2 telões. O nome ficou de quando só existia quórum; não foi renomeado nesta fatia — o único
 * outro chamador é o cockpit do vereador, que passa sem opções nenhumas, e tocar essa assinatura
 * compartilhada é exatamente o tipo de mudança que já derrubou o botão de votar nesta base.
 *
 * `comVotacao` (fatia "demo-tres-consertos" #2b) é uma opção INDEPENDENTE de `comQuorum` — DELIBERADO:
 * `GET /sessoes/:id/votacao-aberta` é uma leitura indexada (uma linha por sessão), não uma das "leituras
 * mais caras do módulo" que justificam manter o cockpit fora de `comQuorum`; amarrar as duas faria o
 * vereador continuar sem a recuperação que ele é quem mais precisa (é ele quem vota) só para não pagar o
 * custo de quórum/tribuna, que ele nunca usou. O cockpit passa `comVotacao: true`; o telão da Mesa (carry,
 * Daouda 12/09/2026) tinha o MESMO buraco de recuperação, por outro ângulo — a borda de
 * `/sessoes/:id/votacao-aberta` exigia papel 'vereador' estrito, então nem ligar `comVotacao` ali
 * adiantaria antes de a política migrar para a camada fina (`sessoes.logic/pode-ver-quorum-da-sessao?`,
 * backend) — e agora também passa `comVotacao: true`. */
export function usePlenario(sessaoId: string, token: string | null, opcoes?: { comQuorum?: boolean; comVotacao?: boolean }) {
  const comQuorum = opcoes?.comQuorum === true;
  const comVotacao = opcoes?.comVotacao === true;
  const [sessao, setSessao] = useState<SessaoOut | null>(null);
  const [estado, setEstado] = useState<EstadoPlenario | null>(null);
  const [conexao, setConexao] = useState<EstadoConexao>("carregando");
  const [erro, setErro] = useState<string | null>(null);
  const lastIdRef = useRef<string | undefined>(undefined);
  const idValido = ID_VALIDO.test(sessaoId);
  // Espelham `estado.falaEventoSeq`/`estado.inscricaoEventoSeq`/`estado.votacaoEventoSeq` de forma
  // SÍNCRONA — `aoFrame` só consegue escrever estado por updater funcional (`setEstado(prev => ...)`), e
  // a busca de tribuna/votação precisa LER os contadores no instante do disparo (T0 do ruling de
  // precedência), antes de qualquer `await`. Zerados a cada nova sessão junto com `estadoInicial` (ver o
  // passo 1 abaixo). Fix round 1 (I2): refs SEPARADOS, não um só — ver a docstring de `TIPOS_EVENTO_FALA`
  // em `plenario-reducer.ts`; `votacaoEventoSeqRef` segue a MESMA disciplina (fatia "demo-tres-consertos" #2b).
  const falaEventoSeqRef = useRef(0);
  const inscricaoEventoSeqRef = useRef(0);
  const votacaoEventoSeqRef = useRef(0);

  useEffect(() => {
    if (semCredencial(token) || !idValido) return; // casos de erro são derivados no retorno (sem setState síncrono no effect)
    const controller = new AbortController();
    let vivo = true;
    let ultimaRebusca = 0;
    let ultimaBuscaVotacao = 0;

    // Fix round 1 (I3): guardas de in-flight INDEPENDENTES por rota (antes era um único `rebuscando`
    // compartilhado, preso até as DUAS buscas resolverem). Com um guarda só, uma `/tribuna` pendurada
    // travava também a re-busca do `/quorum` PARA SEMPRE — o numerador do telão congelava com o badge
    // dizendo "Ao vivo". Agora uma rota pendurada não impede a outra de ser retentada nos próximos
    // ticks; o timeout acima garante que "pendurada" também não dura para sempre.
    let quorumEmVoo = false;
    let tribunaEmVoo = false;
    let votacaoEmVoo = false;

    const buscarQuorum = async () => {
      if (quorumEmVoo) return;
      quorumEmVoo = true;
      // Fix round 2 (A2): timeout composto à mão (sem `AbortSignal.any`/`.timeout`) — ver a docstring
      // da função. `limpar()` no `finally` evita vazar o `setTimeout`/listener a cada chamada.
      const { signal, limpar } = sinalComTimeout(controller.signal, TIMEOUT_REBUSCA_MS);
      try {
        const resp = await apiFetch(`/api/sessoes/${sessaoId}/quorum`, {
          token: token ?? undefined,
          signal,
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
        limpar();
        quorumEmVoo = false;
      }
    };

    /** Busca o snapshot de `GET /sessoes/:id/tribuna`. `seqNoDisparo` é capturado ANTES do fetch — o
     * T0 da regra de PRECEDÊNCIA de `hidratarTribuna` (ver a docstring lá): se um evento de FALA ou de
     * INSCRIÇÃO chegar pelo canal AO VIVO enquanto esta resposta está em voo, a hidratação DESCARTA o
     * campo correspondente do snapshot em vez de ressuscitar quem já desceu da tribuna (ou uma fila já
     * desatualizada). Fix round 1 (I2b): um snapshot com QUALQUER campo descartado não fica esperando a
     * periódica de 30s — pede retentativa assim que o piso `REBUSCA_MIN_MS` (~3s) permitir, pela MESMA
     * `pedidoDeRebusca` que o movimento de presença usa.
     *
     * Fix round 2 (A4a): o PEDIDO de retentativa é decidido DENTRO do updater de `setEstado`, contra
     * `prev` — a MESMA leitura que `hidratarTribuna` usa para decidir o descarte, não os refs lidos por
     * fora. Antes, a comparação contra `falaEventoSeqRef`/`inscricaoEventoSeqRef` corria ANTES do
     * `setEstado`, e os refs só são escritos DENTRO do updater de `aoFrame` (que o React processa no
     * render, não na chegada do frame SSE) — havia uma janela entre um frame chegar e o React aplicá-lo
     * em que os refs ainda liam o valor VELHO. Se a resposta da tribuna resolvesse nesse instante, a
     * checagem de fora não via a divergência (refs velhos) e não pedia retry — mas o updater de
     * `hidratarTribuna`, enfileirado DEPOIS do de `aoFrame`, já rodava contra o `prev` NOVO e descartava
     * o campo mesmo assim. Resultado: descarte sem pedido de retentativa, esperando a periódica de 30s
     * (nunca dado errado — só o atraso que esta fatia existe para eliminar). Ler `prev` no mesmo updater
     * elimina a janela: as duas decisões agora leem o MESMO valor, no MESMO instante. */
    const buscarTribuna = async () => {
      if (tribunaEmVoo) return;
      tribunaEmVoo = true;
      const seqNoDisparo: TribunaEventoSeqNoDisparo = {
        fala: falaEventoSeqRef.current,
        inscricao: inscricaoEventoSeqRef.current,
      };
      // Fix round 2 (A2): timeout composto à mão — ver a docstring de `sinalComTimeout`.
      const { signal, limpar } = sinalComTimeout(controller.signal, TIMEOUT_REBUSCA_MS);
      try {
        const resp = await apiFetch(`/api/sessoes/${sessaoId}/tribuna`, {
          token: token ?? undefined,
          signal,
          cache: "no-store",
        });
        if (!vivo) return;
        if (!resp.ok) {
          setEstado((prev) => (prev ? falharTribuna(prev) : prev));
          return;
        }
        const t = camelizarChaves(await resp.json()) as TribunaOut;
        if (!vivo) return;
        // `hidratarTribuna` é TOTAL e checa a precedência POR CAMPO contra `seqNoDisparo`: nunca lança
        // e nunca ressuscita quem o SSE já desceu da tribuna nesse meio-tempo.
        setEstado((prev) => {
          if (!prev) return prev;
          // MESMA leitura (`prev`) que `hidratarTribuna` usa para decidir o descarte — ver o Fix round 2
          // (A4a) na docstring acima.
          if (prev.falaEventoSeq !== seqNoDisparo.fala || prev.inscricaoEventoSeq !== seqNoDisparo.inscricao) {
            pedidoDeRebusca = true;
          }
          return hidratarTribuna(prev, t, seqNoDisparo);
        });
      } catch {
        if (!vivo) return;
        setEstado((prev) => (prev ? falharTribuna(prev) : prev));
      } finally {
        limpar();
        tribunaEmVoo = false;
      }
    };

    /** Busca o snapshot de `GET /sessoes/:id/votacao-aberta` (fatia "demo-tres-consertos" #2b —
     * RECUPERAÇÃO de estado). `seqNoDisparo` capturado ANTES do fetch — MESMO ruling de `buscarTribuna`
     * (fix I2b/A4a): se um evento de VOTAÇÃO chegar pelo canal enquanto esta resposta está em voo,
     * `hidratarVotacao` descarta o snapshot inteiro em vez de sobrescrever o que o SSE já construiu.
     *
     * 404 é o estado LEGÍTIMO "nenhuma votação aberta agora" (`controllers/votacao-aberta`, backend) —
     * NÃO é falha: passa por `hidratarVotacao(prev, null, ...)`, que é um no-op seguro (o placar já
     * nasce `null`), nunca por `falharVotacao` (reservado a rede/403/500/parse — "não sei", distinto de
     * "sei que não há"). */
    const buscarVotacaoAberta = async () => {
      if (votacaoEmVoo) return;
      votacaoEmVoo = true;
      const seqNoDisparo = votacaoEventoSeqRef.current;
      const { signal, limpar } = sinalComTimeout(controller.signal, TIMEOUT_REBUSCA_MS);
      try {
        const resp = await apiFetch(`/api/sessoes/${sessaoId}/votacao-aberta`, {
          token: token ?? undefined,
          signal,
          cache: "no-store",
        });
        if (!vivo) return;
        if (resp.status === 404) {
          setEstado((prev) => (prev ? hidratarVotacao(prev, null, seqNoDisparo) : prev));
          return;
        }
        if (!resp.ok) {
          setEstado((prev) => (prev ? falharVotacao(prev) : prev));
          return;
        }
        const v = camelizarChaves(await resp.json()) as VotacaoAbertaSnapshot;
        if (!vivo) return;
        setEstado((prev) => (prev ? hidratarVotacao(prev, v, seqNoDisparo) : prev));
      } catch {
        if (!vivo) return;
        setEstado((prev) => (prev ? falharVotacao(prev) : prev));
      } finally {
        limpar();
        votacaoEmVoo = false;
      }
    };

    /** Dispara o par quórum+tribuna que o TELÃO precisa para se reconstruir sozinho (ver a docstring de
     * `comQuorum`). As duas rotas são independentes (guarda de in-flight e timeout próprios — I3): uma
     * pendurada não atrasa nem bloqueia a outra. `ultimaRebusca` só marca QUANDO esta função foi
     * convidada a agir (para o piso/periódica do relógio abaixo) — não espera as buscas terminarem, e
     * por isso não pode mais ser bloqueado pelo par inteiro como antes. */
    const rehidratar = () => {
      if (!comQuorum || !vivo) return;
      ultimaRebusca = Date.now();
      void buscarQuorum();
      void buscarTribuna();
    };

    /** Dispara a recuperação de votação, gated por `comVotacao` (INDEPENDENTE de `comQuorum` — ver a
     * docstring de `comVotacao` acima). Chamada na carga inicial, em toda reconexão e periodicamente pelo
     * MESMO relógio de 500ms de `rehidratar` — sem um segundo `setInterval`. */
    const rehidratarVotacao = () => {
      if (!comVotacao || !vivo) return;
      ultimaBuscaVotacao = Date.now();
      void buscarVotacaoAberta();
    };

    // Pedido de re-busca do quórum, levantado pelo PRÓPRIO reducer (`precisaRehidratar`) — a regra de quais
    // eventos mexem no quórum mora num lugar só, não é redigitada aqui. Escrever `true` dentro do updater é
    // idempotente de propósito: o StrictMode pode invocá-lo duas vezes, e o efeito é o mesmo. Fix round 1
    // (I2b): `buscarTribuna` também escreve aqui quando descarta um campo do snapshot por precedência.
    let pedidoDeRebusca = false;

    /** Achado ao vivo (Daouda, verificação em browser, 12/09/2026): o canal replaya a sessão INTEIRA desde
     * `id: 1` na conexão — não só eventos futuros. O `votacao.aberta` do replay chega ENQUANTO a
     * hidratação inicial (`comVotacao`) ainda está em voo, avança `votacaoEventoSeq`, e a regra de
     * precedência de `hidratarVotacao` (deliberada — não afrouxada aqui: existe para não ressuscitar uma
     * votação que a Mesa já encerrou) descarta o snapshot em voo POR INTEIRO, inclusive `proposicao` — o
     * único campo que o evento não carrega. Sem este gatilho, a Mesa fica até a periódica de 30s sem
     * saber o que está em votação, projetado na parede do plenário (medido: ~30-38s no navegador).
     *
     * Mesmo padrão de `pedidoDeRebusca` acima: o GATILHO é o EVENTO (escrito em `aoFrame`, abaixo), nunca
     * a resposta da própria rebusca — `rehidratarVotacao`/`buscarVotacaoAberta` não escrevem aqui, então
     * uma rebusca não pode reagendar outra (sem laço). SEM o piso `REBUSCA_MIN_MS` do quórum (que existe
     * pra' coalescer a RAJADA de ~21 eventos de presença de uma chamada): `votacao.aberta` não tem esse
     * perfil — no máximo um punhado por sessão inteira, mesmo com replay das votações já encerradas —, e
     * o guarda `votacaoEmVoo` (em `buscarVotacaoAberta`) já impede uma segunda busca sobrepor a primeira
     * se dois pedidos caírem no mesmo tick. O relógio de 500ms só limita a LATÊNCIA do gatilho a essa
     * granularidade — de ~30s (a periódica) pra' quase instantâneo. */
    let pedidoDeRebuscaVotacao = false;

    const aoFrame = (f: { event?: string; data: string; id?: string }) => {
      if (!vivo) return;
      if (f.id) lastIdRef.current = f.id;
      if (!ehTipoPlenario(f.event)) return;
      try {
        const dados = JSON.parse(f.data);
        const evento = { tipo: f.event, seq: f.id ? Number(f.id) : 0, dados } as EventoPlenario;
        if (evento.tipo === "votacao.aberta") pedidoDeRebuscaVotacao = true;
        setEstado((prev) => {
          if (!prev) return prev;
          const proximo = aplicarEvento(prev, evento);
          if (proximo.precisaRehidratar) pedidoDeRebusca = true;
          // espelha os dois contadores para os refs síncronos (ver a docstring de `falaEventoSeqRef`)
          // — todo evento passa por `aplicarEvento`, então este é o ÚNICO ponto que precisa espelhar.
          falaEventoSeqRef.current = proximo.falaEventoSeq;
          inscricaoEventoSeqRef.current = proximo.inscricaoEventoSeq;
          votacaoEventoSeqRef.current = proximo.votacaoEventoSeq;
          return proximo;
        });
      } catch {
        // frame corrompido/forma inesperada: descarta (o servidor já valida na saída; defesa-em-profundidade)
      }
    };

    // Um único relógio governa as três re-buscas, e nenhuma delas roda dentro de um updater de estado:
    //   - REATIVA (debounced): houve movimento de presença e já passou o piso -> re-busca. É o que faz o
    //     número do telão andar durante a chamada, coalescendo a rajada de 21 presenças.
    //   - PERIÓDICA: auto-cura. Cobre o buraco silencioso da retenção de 5 min do canal e qualquer evento
    //     perdido — sem ela, um erro vira permanente e a tela segue exibindo "Ao vivo" com confiança.
    // `comVotacao` (fatia "demo-tres-consertos" #2b) reusa o MESMO relógio de 500ms: a PERIÓDICA (mesma
    // rede de segurança contra a retenção de 5 min do canal) + o pedido EAGER de `votacao.aberta` (achado
    // ao vivo acima) — SEM piso, ver a docstring de `pedidoDeRebuscaVotacao`.
    const relogio = setInterval(() => {
      if (!vivo) return;
      if (comQuorum) {
        const desde = Date.now() - ultimaRebusca;
        if ((pedidoDeRebusca && desde >= REBUSCA_MIN_MS) || desde >= REBUSCA_PERIODICA_MS) {
          pedidoDeRebusca = false;
          void rehidratar();
        }
      }
      if (comVotacao) {
        const desdeVotacao = Date.now() - ultimaBuscaVotacao;
        if (pedidoDeRebuscaVotacao || desdeVotacao >= REBUSCA_PERIODICA_MS) {
          pedidoDeRebuscaVotacao = false;
          void rehidratarVotacao();
        }
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
        // nova sessão -> mesmo zero de `estadoInicial().falaEventoSeq`/`.inscricaoEventoSeq`/`.votacaoEventoSeq`
        falaEventoSeqRef.current = 0;
        inscricaoEventoSeqRef.current = 0;
        votacaoEventoSeqRef.current = 0;
      } catch (e) {
        if (!vivo || controller.signal.aborted) return;
        setConexao("erro");
        // só repassa mensagem controlada (status); erro de rede cru vaza topologia (review seg MINOR-4)
        setErro(e instanceof Error && /^sessao \d+$/.test(e.message) ? "Sessão indisponível." : "Não foi possível carregar a sessão.");
        return;
      }

      // 1b) hidratação do quórum E DA TRIBUNA — BEST-EFFORT e em paralelo ao SSE (não bloqueia a conexão
      // ao vivo). `rehidratar()` busca as duas (ver a docstring dela): o numerador do telão vem SÓ do
      // quórum (nunca funde com delta do SSE — docstring de `hidratarQuorum`), e a tribuna é o read-model
      // desta fatia — quem está com a palavra AGORA, mesmo que a tela abra com a fala já em curso e
      // nenhum evento SSE tenha sido visto ainda. Por isso nenhuma das duas é um disparo único: são
      // re-buscadas sempre que a presença se mexe (debounced), depois de toda reconexão e periodicamente.
      //
      // (não há uma chamada de tribuna separada ao lado do bloco `1c) COMPOSIÇÃO` abaixo: esta MESMA
      // chamada já cobre a carga inicial da tribuna — uma segunda chamada duplicaria o fetch. A tribuna
      // segue a MESMA condição `comQuorum` que o quórum — ver o Ruling do controlador no brief desta
      // fatia — e por isso anda dentro da MESMA função, não de uma cópia da rotina de composição.)
      void rehidratar();
      // 1b-bis) recuperação de votação (fatia "demo-tres-consertos" #2b) — o CASO DA FATIA: abrir a tela
      // (ou reconectar) sem NENHUM evento SSE visto ainda descobre uma votação já aberta no servidor.
      // Gated por `comVotacao`, independente de `comQuorum` — ver a docstring de `comVotacao` acima.
      void rehidratarVotacao();

      // 1c) COMPOSIÇÃO — disparo ÚNICO, ao contrário do quórum e da tribuna. O quórum é re-buscado porque o NÚMERO
      // muda a cada evento de presença; a composição é "quem são os membros da Casa NA DATA desta
      // sessão", que não muda no meio dela (a data de composição é congelada, ver a docstring de
      // `composicao-da-sessao` no backend). Re-buscar a cada frame seria trabalho por tick para um dado
      // estável — e esta é hoje a leitura mais cara do módulo (resolve o roster inteiro).
      //
      // BEST-EFFORT e TOTAL, mesma postura do quórum: rede/403/500/parse não travam o painel nem
      // derrubam o SSE. Sem composição a tribuna cai no rótulo neutro; o que não pode voltar a
      // acontecer é o telão exibir o prefixo do UUID no lugar do nome.
      void (async () => {
        try {
          const resp = await apiFetch(`/api/sessoes/${sessaoId}/composicao`, {
            token: token ?? undefined,
            signal: controller.signal,
            cache: "no-store",
          });
          if (!vivo) return;
          if (!resp.ok) {
            setEstado((prev) => (prev ? falharComposicao(prev) : prev));
            return;
          }
          const c = camelizarChaves(await resp.json()) as ComposicaoSessaoOut;
          if (!vivo) return;
          setEstado((prev) => (prev ? hidratarComposicao(prev, c) : prev));
        } catch {
          if (!vivo) return;
          setEstado((prev) => (prev ? falharComposicao(prev) : prev));
        }
      })();

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
        // errado pelo resto da sessão. Custo: 1 request por queda. `rehidratarVotacao` (fatia
        // "demo-tres-consertos" #2b) segue a MESMA disciplina — uma queda que atravesse a retenção também
        // pode ter perdido a abertura/o encerramento de uma votação.
        if (tentativa > 0) {
          void rehidratar();
          void rehidratarVotacao();
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
      clearInterval(relogio);
      controller.abort();
    };
  }, [sessaoId, token, idValido, comQuorum, comVotacao]);

  // casos de erro derivados (mantêm o effect livre de setState síncrono)
  if (semCredencial(token)) return { sessao: null, estado: null, conexao: "erro" as EstadoConexao, erro: "Sem credencial de sessão (token)." };
  if (!idValido) return { sessao: null, estado: null, conexao: "erro" as EstadoConexao, erro: "Identificador de sessão inválido." };
  return { sessao, estado, conexao, erro };
}
