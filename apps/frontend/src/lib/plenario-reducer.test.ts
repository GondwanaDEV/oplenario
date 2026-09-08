import { describe, it, expect } from "vitest";
import { aplicarEvento, estadoInicial, falharComposicao, falharQuorum, falharTribuna, hidratarComposicao, hidratarQuorum, hidratarTribuna, identidadeDe, numeroDoTelao, type EstadoPlenario, vistaDoQuorum } from "./plenario-reducer";
import { derivarMeuVoto } from "./meu-voto-vista";
import type { EventoPlenario, SessaoOut } from "./contrato";
import type { QuorumSessaoOut, TribunaOut } from "./contrato-sessoes.gen";

// ---- fixtures mínimas (forma fiel ao wire) ----

const sessao = (over: Partial<SessaoOut> = {}): SessaoOut => ({
  id: "s1",
  "sessao-legislativa-id": "sl1",
  "tipo-sessao": "ordinaria",
  "numero-sequencial": 14,
  estado: "agendada",
  modalidade: "presencial",
  delibera: true,
  "transmite-publica": true,
  "gera-ata-regimental": true,
  "permite-voto-secreto": false,
  "permite-modalidade-remota": false,
  "agendada-para": "2026-05-21T22:00:00Z",
  "aberta-em": null,
  "encerrada-em": null,
  "motivo-nao-realizada": null,
  ...over,
});

// aplica uma sequência de eventos a partir do estado inicial de `s`
const reduzir = (s: SessaoOut, eventos: EventoPlenario[]): EstadoPlenario =>
  eventos.reduce(aplicarEvento, estadoInicial(s));

describe("estadoInicial", () => {
  it("semeia o estado a partir da sessão e começa com tudo vazio", () => {
    const e = estadoInicial(sessao({ estado: "aberta" }));
    expect(e.estado).toBe("aberta");
    expect(e.presentes).toEqual([]);
    expect(e.oradorAtual).toBeNull();
    expect(e.inscritos).toEqual([]);
    expect(e.ultimoSeq).toBe(0);
  });
});

describe("sessao.transicionou", () => {
  it("avança o estado da sessão para o `para` da transição", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "sessao.transicionou", seq: 1, dados: { "sessao-id": "s1", de: "aberta", para: "suspensa" } },
    ]);
    expect(e.estado).toBe("suspensa");
    expect(e.ultimoSeq).toBe(1);
  });
});

describe("presenca.registrada — quórum por conjunto", () => {
  const pres = (seq: number, vereador: string, tipo: string): EventoPlenario => ({
    tipo: "presenca.registrada",
    seq,
    dados: { "sessao-id": "s1", "vereador-id": vereador, tipo, modalidade: "plenario", fonte: "manual_secretaria", "ocorrido-em": "2026-05-21T22:01:00Z" },
  });

  it("entrada/retorno/mudanca_modalidade marcam presente; saida remove", () => {
    const e = reduzir(sessao(), [pres(1, "v1", "entrada"), pres(2, "v2", "entrada"), pres(3, "v1", "saida")]);
    expect(e.presentes).toEqual(["v2"]);
  });

  it("é idempotente — entrada repetida do mesmo vereador não duplica (entrega at-least-once)", () => {
    const e = reduzir(sessao(), [pres(1, "v1", "entrada"), pres(2, "v1", "entrada")]);
    expect(e.presentes).toEqual(["v1"]);
  });

  it("retorno após saída recoloca o vereador", () => {
    const e = reduzir(sessao(), [pres(1, "v1", "entrada"), pres(2, "v1", "saida"), pres(3, "v1", "retorno")]);
    expect(e.presentes).toEqual(["v1"]);
  });
});

describe("tribuna — fala iniciada/cronômetro/encerrada", () => {
  const iniciar: EventoPlenario = {
    tipo: "fala.iniciada",
    seq: 1,
    dados: { "fala-id": "f1", "sessao-id": "s1", "orador-id": "v1", "tipo-fala": "discussao", fase: "ordem_do_dia", "iniciou-em": "2026-05-21T22:10:00Z" },
  };

  it("fala.iniciada fixa o orador atual e zera os marcos do cronômetro", () => {
    const e = reduzir(sessao(), [iniciar]);
    expect(e.oradorAtual).toMatchObject({ falaId: "f1", oradorId: "v1", iniciouEm: "2026-05-21T22:10:00Z" });
    expect(e.marcosCronometro).toEqual([]);
  });

  it("fala.cronometro acumula marcos da fala em curso", () => {
    const e = reduzir(sessao(), [
      iniciar,
      { tipo: "fala.cronometro", seq: 2, dados: { "fala-id": "f1", "sessao-id": "s1", tipo: "pausada", "ocorrido-em": "2026-05-21T22:11:00Z" } },
      { tipo: "fala.cronometro", seq: 3, dados: { "fala-id": "f1", "sessao-id": "s1", tipo: "retomada", "ocorrido-em": "2026-05-21T22:11:30Z" } },
    ]);
    expect(e.marcosCronometro.map((m) => m.tipo)).toEqual(["pausada", "retomada"]);
  });

  it("ignora marco de cronômetro de outra fala (não a em curso)", () => {
    const e = reduzir(sessao(), [
      iniciar,
      { tipo: "fala.cronometro", seq: 2, dados: { "fala-id": "fOUTRA", "sessao-id": "s1", tipo: "pausada", "ocorrido-em": "2026-05-21T22:11:00Z" } },
    ]);
    expect(e.marcosCronometro).toEqual([]);
  });

  it("fala.encerrada limpa o orador atual e guarda o tempo usado", () => {
    const e = reduzir(sessao(), [
      iniciar,
      { tipo: "fala.encerrada", seq: 2, dados: { "fala-id": "f1", "sessao-id": "s1", "tempo-segundos": 248, "encerrou-em": "2026-05-21T22:14:08Z" } },
    ]);
    expect(e.oradorAtual).toBeNull();
    expect(e.ultimaFalaEncerrada).toMatchObject({ falaId: "f1", tempoSegundos: 248 });
  });
});

describe("inscritos — fila ordenada", () => {
  const insc = (seq: number, id: string, vereador: string, ordem: number): EventoPlenario => ({
    tipo: "inscricao.registrada",
    seq,
    dados: { "inscricao-id": id, "sessao-id": "s1", "vereador-id": vereador, "origem-inscricao": "manual", fase: "ordem_do_dia", ordem },
  });

  it("registra inscritos mantendo a ordem por `ordem` (não por chegada)", () => {
    const e = reduzir(sessao(), [insc(1, "i2", "v2", 2), insc(2, "i1", "v1", 1)]);
    expect(e.inscritos.map((i) => i.vereadorId)).toEqual(["v1", "v2"]);
  });

  it("desistência remove o inscrito pela inscricao-id", () => {
    const e = reduzir(sessao(), [
      insc(1, "i1", "v1", 1),
      insc(2, "i2", "v2", 2),
      { tipo: "inscricao.desistida", seq: 3, dados: { "inscricao-id": "i1", "sessao-id": "s1" } },
    ]);
    expect(e.inscritos.map((i) => i.vereadorId)).toEqual(["v2"]);
  });

  it("registro repetido da mesma inscrição não duplica (at-least-once)", () => {
    const e = reduzir(sessao(), [insc(1, "i1", "v1", 1), insc(2, "i1", "v1", 1)]);
    expect(e.inscritos).toHaveLength(1);
  });
});

describe("ultimoSeq — rastreia o maior seq visto (Last-Event-ID do resume)", () => {
  it("guarda o maior seq mesmo que um evento fora de ordem chegue depois", () => {
    const e = reduzir(sessao(), [
      { tipo: "sessao.transicionou", seq: 5, dados: { "sessao-id": "s1", de: "agendada", para: "aberta" } },
      { tipo: "sessao.transicionou", seq: 3, dados: { "sessao-id": "s1", de: "aberta", para: "suspensa" } },
    ]);
    expect(e.ultimoSeq).toBe(5);
  });
});

describe("votação ao vivo — placar (§22.6 sigilo)", () => {
  it("votacao.aberta cria o placar zerado com a modalidade (nominal)", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "nominal", "quorum-tipo": "maioria_simples" } },
    ]);
    expect(e.placar).toEqual({
      votacaoId: "vt1", modalidade: "nominal", objetoTipo: "proposicao", encerrada: false,
      votosNominais: {}, votosSecretos: 0, resultado: null, totais: null, baseMembros: null,
    });
  });

  it("voto.registrado nominal grava o voto por vereador (quem votou o quê); re-voto sobrescreve", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "nominal", "quorum-tipo": "maioria_simples" } },
      { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd1", voto: "sim" } },
      { tipo: "voto.registrado", seq: 3, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd2", voto: "nao" } },
      { tipo: "voto.registrado", seq: 4, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd1", voto: "abstencao" } },
    ]);
    expect(e.placar?.votosNominais).toEqual({ vd1: "abstencao", vd2: "nao" });
    expect(e.placar?.votosSecretos).toBe(0);
  });

  it("voto.registrado secreto SÓ incrementa o contador — nunca expõe identidade (sigilo)", () => {
    const e = reduzir(sessao({ estado: "aberta", "permite-voto-secreto": true }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "secreta", "quorum-tipo": "maioria_absoluta" } },
      { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "secreta" } },
      { tipo: "voto.registrado", seq: 3, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "secreta" } },
    ]);
    expect(e.placar?.votosSecretos).toBe(2);
    expect(e.placar?.votosNominais).toEqual({});
  });

  it("§22.6 fail-closed: numa votação ABERTA secreta, um voto que chega com vereador-id NÃO o registra — conta anônimo", () => {
    // defesa-em-profundidade: a modalidade que vale é a da ABERTURA, não a do evento individual (que poderia
    // vir adulterado/malformado). A identidade que vazou no fio é DESCARTADA; só o contador anônimo sobe.
    const e = reduzir(sessao({ estado: "aberta", "permite-voto-secreto": true }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "secreta", "quorum-tipo": "maioria_absoluta" } },
      { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd1", voto: "sim" } },
    ]);
    expect(e.placar?.votosNominais).toEqual({}); // identidade descartada
    expect(e.placar?.votosSecretos).toBe(1); // contado anonimamente
  });

  it("ignora voto de uma votação que não é a corrente (votacao-id diferente)", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "nominal", "quorum-tipo": "maioria_simples" } },
      { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "OUTRA", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd9", voto: "sim" } },
    ]);
    expect(e.placar?.votosNominais).toEqual({});
  });

  it("votacao.encerrada marca encerrada + grava resultado/totais/base (agregado público)", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "votacao.aberta", seq: 1, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", "objeto-tipo": "proposicao", "objeto-id": "p1",
        modalidade: "nominal", "quorum-tipo": "maioria_simples" } },
      { tipo: "voto.registrado", seq: 2, dados: { "votacao-id": "vt1", "sessao-id": "s1", modalidade: "nominal", "vereador-id": "vd1", voto: "sim" } },
      { tipo: "votacao.encerrada", seq: 3, dados: {
        "votacao-id": "vt1", "sessao-id": "s1", resultado: "aprovada", modalidade: "nominal",
        "total-sim": 6, "total-nao": 3, "total-abstencao": 1, "base-membros": 11 } },
    ]);
    expect(e.placar?.encerrada).toBe(true);
    expect(e.placar?.resultado).toBe("aprovada");
    expect(e.placar?.totais).toEqual({ sim: 6, nao: 3, abstencao: 1 });
    expect(e.placar?.baseMembros).toBe(11);
    expect(e.placar?.votosNominais).toEqual({ vd1: "sim" }); // preserva o nominal acumulado
  });

  it("votacao.encerrada sem aberta vista (reconexão) constrói o placar do agregado", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [
      { tipo: "votacao.encerrada", seq: 9, dados: {
        "votacao-id": "vt7", "sessao-id": "s1", resultado: "rejeitada", modalidade: "secreta" } },
    ]);
    expect(e.placar?.votacaoId).toBe("vt7");
    expect(e.placar?.encerrada).toBe(true);
    expect(e.placar?.resultado).toBe("rejeitada");
    expect(e.placar?.totais).toEqual({ sim: null, nao: null, abstencao: null });
  });
});


// ---------------------------------------------------------------------------------------------------
// QUÓRUM NO TELÃO (Etapa 4) + a REGRESSÃO da revisão adversarial da branch `chamada-etapa4-quorum-hero`.
//
// O eixo comum dos achados graves era UM: a primeira versão fundiu "a base opaca do snapshot" com "o delta
// ao vivo" e, para reconciliar as duas sem ids (a rota é MAGRA de propósito — não trafega vereador-id),
// teve de (a) redefinir `presentes`, campo COMPARTILHADO com o cockpit do vereador, (b) somar/subtrair um
// contador cego, e (c) cortar eventos por comparação LEXICOGRÁFICA de instante. Cada um é um achado.
//
// A correção é estrutural: o NUMERADOR do telão vem SÓ do servidor (`presentesTotal`, re-buscado), e
// `presentes` volta a ser o conjunto nominal do SSE de que o cockpit depende. Sem fusão não há aritmética
// no cliente, e os achados somem por construção em vez de serem remendados um a um.
// ---------------------------------------------------------------------------------------------------
describe("quórum do telão — hidratação de GET /sessoes/:id/quorum", () => {
  // Vocabulário LIDO DA FONTE: `modalidade` de `sessoes/logic/modalidades-presenca` (#{"plenario" "remoto"})
  // e `fonte` de `logic/fontes-presenca`. O `RegistradaPayload` foi apertado para `km/enum-de` justamente
  // porque valores inventados mantiveram um read-model morto verde por meses neste repo (memória
  // `oplenario-fixture-vocabulario-ficticio`) — um fixture que os redigita de memória é o próximo episódio.
  const presEm = (seq: number, vereador: string, tipo: string, ocorridoEm: string): EventoPlenario => ({
    tipo: "presenca.registrada",
    seq,
    dados: { "sessao-id": "s1", "vereador-id": vereador, tipo, modalidade: "plenario", fonte: "manual_secretaria", "ocorrido-em": ocorridoEm },
  });

  const snap = (over: Partial<QuorumSessaoOut> = {}): QuorumSessaoOut => ({
    sessaoId: "s1",
    sessaoEstado: "aberta",
    instante: "2026-05-21T22:05:00Z",
    dataDeComposicao: "2026-05-21",
    composicaoResolvidaEm: "2026-05-21T22:05:00Z",
    semRegistroDePresenca: false,
    quorum: { presentesPlenario: 7, presentesRemoto: 0, presentesTotal: 7, membrosDaCasa: 21, presencasForaDoRoster: 0 },
    ...over,
  });

  const aberta = () => estadoInicial(sessao({ estado: "aberta" }));

  it("Q1 — hidratação dá numerador E denominador ao telão: 7 de 21", () => {
    const e = hidratarQuorum(aberta(), snap());
    expect(numeroDoTelao(e)).toBe(7);
    expect(e.quorum?.membrosDaCasa).toBe(21);
    expect(e.quorumStatus).toBe("ok");
  });

  it("Q2 — sem hidratação o telão não inventa número: fica em 'carregando', não em zero", () => {
    const e = reduzir(sessao({ estado: "aberta" }), [presEm(1, "v1", "entrada", "2026-05-21T22:01:00Z")]);
    expect(numeroDoTelao(e)).toBeNull();
    expect(e.quorumStatus).toBe("carregando");
  });

  it("Q3 — hidratar de novo com o mesmo snapshot é idempotente", () => {
    const s = snap();
    const duas = hidratarQuorum(hidratarQuorum(aberta(), s), s);
    expect(numeroDoTelao(duas)).toBe(7);
  });

  it("R1 (CRÍTICO) — a hidratação NÃO pode podar `presentes`: o cockpit do vereador lê esse campo de forma NOMINAL", () => {
    // `usePlenario` alimenta DUAS telas. `meu-voto-vista.ts` faz `estado.presentes.includes(meuVereadorId)`,
    // e esse booleano gate o ciclo `sem-presenca` -> o botão de votar não é oferecido. Cenário real: o
    // vereador confirma presença às 22:01, o snapshot de quórum chega avaliado às 22:05, a poda por instante
    // o remove de `presentes` -> o BOTÃO DE VOTAR SOME do celular dele, com votação nominal aberta.
    const meuId = "vX";
    const depoisDoSse = aplicarEvento(aberta(), presEm(1, meuId, "entrada", "2026-05-21T22:01:00Z"));
    expect(derivarMeuVoto(depoisDoSse, meuId).presente).toBe(true);
    expect(derivarMeuVoto(hidratarQuorum(depoisDoSse, snap()), meuId).presente).toBe(true);
  });

  it("R2 (CRÍTICO) — `mudanca_modalidade` de quem já está no snapshot não conta a mesma pessoa duas vezes", () => {
    // O backend conta o ÚLTIMO evento por vereador: plenário -> remoto move a pessoa de coluna e o TOTAL
    // não muda. Com base opaca, o cliente somava +1 a cada troca de modalidade numa sessão híbrida.
    const hidratado = hidratarQuorum(aberta(), snap());
    const depois = aplicarEvento(hidratado, presEm(1, "vJaPresente", "mudanca_modalidade", "2026-05-21T22:06:00Z"));
    expect(numeroDoTelao(depois)).toBe(7);
    expect(depois.precisaRehidratar).toBe(true); // e o servidor é quem recalcula
  });

  it("R3 (CRÍTICO) — registro RETROATIVO após a hidratação não é descartado para sempre", () => {
    // `ocorrido-em` vem do CLIENTE em POST /sessoes/:id/presenca, e o domínio recusa só futuro, outro dia
    // civil e pós-encerramento: registrar no passado é fluxo LEGÍTIMO ("ele saiu antes do intervalo,
    // registra aí"). O corte `ocorridoEm <= instante -> descarta` jogava o evento fora, e como não havia
    // re-hidratação nenhuma o erro era PERMANENTE: o telão divergia da ata pelo resto da sessão.
    const hidratado = hidratarQuorum(aberta(), snap()); // instante 22:05
    const retro = aplicarEvento(hidratado, presEm(1, "vAtrasado", "entrada", "2026-05-21T21:58:00Z"));
    expect(retro.presentes).toContain("vAtrasado");
    expect(retro.precisaRehidratar).toBe(true);
  });

  it("R4 (MAJOR) — `saida` entregue DUAS vezes (at-least-once, seq distinta) não decrementa duas vezes", () => {
    const hidratado = hidratarQuorum(aberta(), snap());
    const uma = aplicarEvento(hidratado, presEm(1, "vSaiu", "saida", "2026-05-21T22:06:00Z"));
    const duas = aplicarEvento(uma, presEm(2, "vSaiu", "saida", "2026-05-21T22:06:00Z")); // MESMO fato, seq nova
    expect(numeroDoTelao(duas)).toBe(numeroDoTelao(uma));
    expect(duas.presentes).toEqual(uma.presentes);
  });

  it("R5 (MAJOR) — snapshot de forma inválida não produz NaN nem lança: `hidratarQuorum` é TOTAL", () => {
    // O hook fazia `camelizarChaves(...) as QuorumSessaoOut` — cast puro, zero validação. Um campo
    // ausente/renomeado virava `undefined` (não `null`), ATRAVESSAVA a guarda `!== null` da página, e
    // `undefined + undefined = NaN` chegava ao telão como "NaN de undefined" — sem exceção, porque
    // aritmética com undefined não lança. E `quorum` ausente LANÇAVA de dentro de um updater de estado.
    const base = aberta();
    const semQuorum = hidratarQuorum(base, { instante: "2026-05-21T22:05:00Z" } as unknown as QuorumSessaoOut);
    expect(semQuorum.quorum).toBeNull();
    expect(semQuorum.quorumStatus).toBe("indisponivel");

    const campoAusente = hidratarQuorum(base, snap({
      quorum: { presentesPlenario: 1, presentesRemoto: 0, presentesTotal: 1, presencasForaDoRoster: 0 },
    } as unknown as Partial<QuorumSessaoOut>));
    expect(campoAusente.quorum).toBeNull();
    expect(numeroDoTelao(campoAusente)).toBeNull();
  });

  it("R6 (MÉDIO) — a ordem entre eventos do MESMO vereador é NUMÉRICA, nunca lexicográfica", () => {
    const hidratado = hidratarQuorum(aberta(), snap());
    // (a) fora de ordem de verdade (instantes distintos): a `entrada` atrasada está SUPERADA pela `saida`
    // mais nova e não pode ressuscitar o vereador — reentrega fora de ordem acontece no resume por
    // Last-Event-ID, e o próprio repo documenta que o mesmo fato pode chegar duas vezes com seq distinta.
    const saiu = aplicarEvento(hidratado, presEm(1, "vB", "saida", "2026-05-21T22:10:00Z"));
    const atrasada = aplicarEvento(saiu, presEm(2, "vB", "entrada", "2026-05-21T22:03:00Z"));
    expect(atrasada.presentes).not.toContain("vB");

    // (b) EMPATE na milissegunda — o caso em que a comparação de texto invertia o tempo: '...07.412Z'
    // (navegador, 3 casas) vs '...07.412683Z' (Instant/now, 6 casas), e em ASCII 'Z'(0x5A) > '4'(0x34).
    // Numericamente (`Date.parse` trunca em ms) os dois são o MESMO instante, e o desempate é a ORDEM DE
    // CHEGADA no canal — a ordenação do próprio servidor —, jamais a ordem alfabética da string.
    const s1 = aplicarEvento(hidratado, presEm(1, "vC", "saida", "2026-05-21T22:05:07.412683Z"));
    const e2 = aplicarEvento(s1, presEm(2, "vC", "entrada", "2026-05-21T22:05:07.412Z"));
    expect(e2.presentes).toContain("vC");
  });

  it("R7 — hidratar com snapshot MAIS NOVO depois de deltas ao vivo usa o número do servidor, não a soma", () => {
    const comDeltas = [1, 2, 3].reduce(
      (e, i) => aplicarEvento(e, presEm(i, `v${i}`, "entrada", "2026-05-21T22:06:00Z")),
      aberta(),
    );
    const hidratado = hidratarQuorum(comDeltas, snap({
      instante: "2026-05-21T22:07:00Z",
      quorum: { presentesPlenario: 10, presentesRemoto: 0, presentesTotal: 10, membrosDaCasa: 21, presencasForaDoRoster: 0 },
    }));
    expect(numeroDoTelao(hidratado)).toBe(10); // NÃO 13: o servidor já contou os três
    expect(hidratado.presentes).toEqual(["v1", "v2", "v3"]); // e o conjunto nominal do SSE fica INTACTO
  });

  it("R8 (MÉDIO) — o cliente NÃO soma quórum: o numerador é `presentesTotal`", () => {
    // Se `logic/estados-presentes` ganhar uma terceira categoria positiva, o servidor conta N e um cliente
    // que soma dois campos subconta em silêncio. O fixture é incoerente de propósito: a soma dá 5, o
    // servidor diz 9 — e o telão tem de exibir 9.
    const e = hidratarQuorum(aberta(), snap({
      quorum: { presentesPlenario: 3, presentesRemoto: 2, presentesTotal: 9, membrosDaCasa: 21, presencasForaDoRoster: 0 },
    }));
    expect(numeroDoTelao(e)).toBe(9);
  });

  it("R9 (MÉDIO) — a falha da borda é um TERCEIRO estado: nunca 'zero presentes', sempre 'não sei'", () => {
    const e = falharQuorum(aberta());
    expect(e.quorumStatus).toBe("indisponivel");
    expect(numeroDoTelao(e)).toBeNull();
    // e uma falha DEPOIS de um snapshot bom degrada, não zera — um número de segundos atrás vale mais que nenhum
    const jaHidratado = hidratarQuorum(aberta(), snap());
    expect(falharQuorum(jaHidratado).quorumStatus).toBe("ok");
    expect(numeroDoTelao(falharQuorum(jaHidratado))).toBe(7);
  });

  it("R10 (MÉDIO) — `sem-registro-de-presenca` e `presencas-fora-do-roster` chegam à vista (não são descartados)", () => {
    // Os dois campos vinham sendo baixados e jogados fora. `presencas-fora-do-roster` é o que explica um
    // "22 de 21" no telão (presença sem assento conta no numerador, não no denominador);
    // `sem-registro-de-presenca` é o que distingue "ninguém registrou ainda" de "a Casa faltou".
    const e = hidratarQuorum(aberta(), snap({
      semRegistroDePresenca: true,
      quorum: { presentesPlenario: 22, presentesRemoto: 0, presentesTotal: 22, membrosDaCasa: 21, presencasForaDoRoster: 1 },
    }));
    const v = vistaDoQuorum(e);
    expect(v).toEqual({ status: "ok", presentes: 22, membrosDaCasa: 21, foraDoRoster: 1, semRegistro: true });
  });
});


describe("composição — o índice de nomes sobrevive ao fluxo de eventos", () => {
  // `sessao` é o helper de topo deste arquivo; `aberta` existe só dentro de outro describe, então
  // este bloco define o seu.
  const aberta = () => estadoInicial(sessao({ estado: "aberta" }));

  const composicao = {
    sessaoId: "s1",
    sessaoEstado: "aberta",
    dataDeComposicao: "2026-09-01",
    composicaoResolvidaEm: "2026-09-01T23:00:00Z",
    membros: [
      { vereadorId: "v1", nomeParlamentar: "Ana Ribeiro", cargoMesa: "presidente" },
      { vereadorId: "v2", nomeParlamentar: null, cargoMesa: null },
    ],
  } as never;

  it("indexa por vereadorId e resolve nome + cargo", () => {
    const e = hidratarComposicao(aberta(), composicao);
    expect(e.composicaoStatus).toBe("ok");
    expect(identidadeDe(e, "v1")).toEqual({ nomeParlamentar: "Ana Ribeiro", cargoMesa: "presidente" });
  });

  it("membro SEM nome parlamentar resolve para null — a tela cai no rótulo neutro, não num nome vazio", () => {
    const e = hidratarComposicao(aberta(), composicao);
    expect(identidadeDe(e, "v2")).toBeNull();
    expect(identidadeDe(e, "id-que-nao-existe")).toBeNull();
  });

  it("sem composição carregada, resolver não lança — devolve null", () => {
    expect(identidadeDe(aberta(), "v1")).toBeNull();
  });

  it("corpo de forma inesperada degrada, nunca lança (este updater roda na fase de RENDER)", () => {
    expect(() => hidratarComposicao(aberta(), {} as never)).not.toThrow();
    expect(hidratarComposicao(aberta(), {} as never).composicaoStatus).toBe("indisponivel");
    // e uma falha DEPOIS de um índice bom degrada, não zera
    const ja = hidratarComposicao(aberta(), composicao);
    expect(falharComposicao(ja).composicaoStatus).toBe("ok");
    expect(identidadeDe(falharComposicao(ja), "v1")).not.toBeNull();
  });

  // A GARANTIA ESTRUTURAL: `aplicarEvento` compõe estado por spread de `base`. Um `case` futuro que
  // devolvesse um literal sem esse spread apagaria o índice de nomes em silêncio, e o telão voltaria ao
  // rótulo neutro no meio da sessão — sem erro, sem log, sem teste reprovando. É isto que trava aqui.
  it("o índice sobrevive a um evento aplicado depois dele", () => {
    const comNomes = hidratarComposicao(aberta(), composicao);
    const depois = aplicarEvento(comNomes, {
      tipo: "presenca.registrada",
      seq: 1,
      dados: { "sessao-id": "s1", "vereador-id": "v1", tipo: "entrada", "ocorrido-em": "2026-09-01T23:05:00Z" },
    } as never);
    expect(identidadeDe(depois, "v1")).toEqual({ nomeParlamentar: "Ana Ribeiro", cargoMesa: "presidente" });
    expect(depois.composicaoStatus).toBe("ok");
  });
});

// #7 do ledger de prontidão: o telão perde a tribuna em três momentos — F5, abrir a tela com a fala já
// em curso, e queda de rede > 5 min. `hidratarTribuna` é o read-model que reconstrói `oradorAtual` /
// `marcosCronometro` / `inscritos` a partir de `GET /sessoes/:id/tribuna`, sem depender de nenhum
// evento SSE ter sido visto.
describe("tribuna — o read-model reconstrói quem está com a palavra", () => {
  const aberta = () => estadoInicial(sessao({ estado: "aberta" }));

  // captura os dois contadores de precedência, na forma que `hidratarTribuna`/o hook esperam (fix round 1, I2)
  const capturar = (e: EstadoPlenario) => ({ fala: e.falaEventoSeq, inscricao: e.inscricaoEventoSeq });
  const seq0 = { fala: 0, inscricao: 0 };

  const falaIniciada = (seq: number, oradorId: string, falaId = "f1"): EventoPlenario => ({
    tipo: "fala.iniciada",
    seq,
    dados: { "fala-id": falaId, "sessao-id": "s1", "orador-id": oradorId, "tipo-fala": "principal", fase: "ordem_do_dia", "iniciou-em": "2026-09-07T22:00:00Z" },
  });

  const falaEncerrada = (seq: number, falaId = "f1"): EventoPlenario => ({
    tipo: "fala.encerrada",
    seq,
    dados: { "fala-id": falaId, "sessao-id": "s1", "tempo-segundos": 120, "encerrou-em": "2026-09-07T22:02:00Z" },
  });

  const snap = (over: Partial<TribunaOut> = {}): TribunaOut => ({
    sessaoId: "s1",
    oradorAtual: { falaId: "f2", oradorId: "vSnapshot", tipoFala: "principal", fase: "ordem_do_dia", iniciouEm: "2026-09-07T21:55:00Z", inscricaoId: null },
    marcosCronometro: [{ tipo: "pausada", ocorridoEm: "2026-09-07T21:56:00Z", segundosAdicionais: null }],
    // já na ordem que o SERVIDOR manda (fase ASC, ordem ASC) — o cliente NÃO reordena (fix round 1, I1;
    // ver T7 abaixo para o caso que reprova se o sort voltar)
    inscritos: [
      { inscricaoId: "i1", vereadorId: "v1", origemInscricao: "pre_sessao_app", fase: "ordem_do_dia", ordem: 1 },
      { inscricaoId: "i2", vereadorId: "v2", origemInscricao: "pre_sessao_app", fase: "ordem_do_dia", ordem: 2 },
    ],
    ...over,
  });

  it("T1 — o CASO DA FATIA: abrir a tela com a fala já em curso resolve o orador SEM nenhum evento SSE", () => {
    const e = hidratarTribuna(aberta(), snap(), seq0);
    expect(e.oradorAtual).toEqual({ falaId: "f2", oradorId: "vSnapshot", tipoFala: "principal", fase: "ordem_do_dia", iniciouEm: "2026-09-07T21:55:00Z" });
    expect(e.marcosCronometro).toEqual([{ tipo: "pausada", ocorridoEm: "2026-09-07T21:56:00Z", segundosAdicionais: null }]);
    // e a fila chega na MESMA ordem em que o servidor mandou
    expect(e.inscritos.map((i) => i.inscricaoId)).toEqual(["i1", "i2"]);
  });

  it("T2 — `oradorAtual: null` no snapshot é um estado VÁLIDO (tribuna livre), não uma forma inesperada", () => {
    const comFala = aplicarEvento(aberta(), falaIniciada(1, "vAoVivo"));
    const e = hidratarTribuna(comFala, snap({ oradorAtual: null, marcosCronometro: [] }), capturar(comFala));
    expect(e.oradorAtual).toBeNull();
  });

  it("T3 (MAJOR) — corpo de forma inesperada não lança e não inventa orador: TOTAL, como hidratarComposicao/hidratarQuorum", () => {
    const base = aberta();
    expect(() => hidratarTribuna(base, {} as never, seq0)).not.toThrow();
    const semForma = hidratarTribuna(base, {} as never, seq0);
    expect(semForma.oradorAtual).toBeNull(); // não muda o que já havia (estado inicial: ninguém)
    expect(semForma.inscritos).toEqual([]);

    // um orador já visto pelo SSE sobrevive a uma resposta de forma torta
    const comFala = aplicarEvento(base, falaIniciada(1, "vAoVivo"));
    const naoLanca = hidratarTribuna(comFala, { oradorAtual: "nao-e-um-objeto" } as unknown as TribunaOut, capturar(comFala));
    expect(naoLanca.oradorAtual?.oradorId).toBe("vAoVivo");

    // um item torto na lista de inscritos não descarta os vizinhos válidos
    const comItemTorto = hidratarTribuna(
      base,
      snap({ inscritos: [{ inscricaoId: "iOk", vereadorId: "vOk", origemInscricao: "pre_sessao_app", fase: "ordem_do_dia", ordem: 1 }, { inscricaoId: 42 } as never] }),
      seq0,
    );
    expect(comItemTorto.inscritos).toEqual([{ inscricaoId: "iOk", vereadorId: "vOk", fase: "ordem_do_dia", ordem: 1 }]);
  });

  it("T4 (CRÍTICO) — PRECEDÊNCIA: um `fala.encerrada` chegado DEPOIS do disparo descarta só o CAMPO de fala do snapshot em voo", () => {
    // Anatomia do ruling: T0 dispara o GET com alguém na tribuna; entre T0 e a resposta (T1) o SSE
    // entrega `fala.encerrada` — a Mesa encerrou a fala. Se a hidratação aplicasse o snapshot de T0
    // (o molde "servidor sempre vence" de `hidratarQuorum`), o telão RESSUSCITARIA na transmissão
    // pública um orador que já desceu da tribuna. A regra correta é descartar: o SSE é mais novo.
    const comFala = aplicarEvento(aberta(), falaIniciada(1, "vAoVivo"));
    const seqNoDisparo = capturar(comFala); // capturado pelo hook ANTES do fetch (T0)

    const encerrada = aplicarEvento(comFala, falaEncerrada(2)); // chega em T1, antes da resposta HTTP

    const resultado = hidratarTribuna(encerrada, snap(), seqNoDisparo); // resposta de T0 chega em T2
    expect(resultado.oradorAtual).toBeNull(); // continua encerrado — não ressuscitado pelo snapshot velho
    expect(resultado.marcosCronometro).toEqual([]); // idem: `fala.encerrada` já zerou os marcos

    // contraprova: SEM o evento de por meio, o mesmo snapshot é aceito normalmente (a mutação que troca
    // a regra por "servidor sempre vence" faz este describe passar mas o T4 acima reprovar)
    const semEventoNoMeio = hidratarTribuna(comFala, snap(), seqNoDisparo);
    expect(semEventoNoMeio.oradorAtual?.oradorId).toBe("vSnapshot");
  });

  it("T4b (I2) — um `inscricao.registrada` alheio em voo NÃO derruba o orador: o descarte agora é POR CAMPO", () => {
    // O defeito que o Fix round 1 mata: antes, um único contador cobria os 5 tipos e QUALQUER um deles
    // em voo descartava o snapshot INTEIRO — inclusive `oradorAtual`, apagando quem está com a palavra
    // por até 30s. Cenário: telão em F5 no meio de uma fala; entre o disparo e a resposta, um vereador
    // se inscreve (evento de INSCRIÇÃO, não de fala).
    const comFala = aplicarEvento(aberta(), falaIniciada(1, "vAoVivo"));
    const seqNoDisparo = capturar(comFala); // T0

    const comInscricaoAlheia = aplicarEvento(comFala, {
      tipo: "inscricao.registrada",
      seq: 2,
      dados: { "inscricao-id": "iNova", "sessao-id": "s1", "vereador-id": "v9", "origem-inscricao": "pre_sessao_app", fase: "ordem_do_dia", ordem: 3 },
    }); // chega em T1, antes da resposta HTTP — só mexe em `inscricaoEventoSeq`

    const resultado = hidratarTribuna(comInscricaoAlheia, snap(), seqNoDisparo); // resposta de T0 em T2
    // o snapshot de FALA é APLICADO normalmente: nenhum evento de FALA chegou no meio, só um de
    // INSCRIÇÃO — o campo de orador não tem por que ser descartado. Com o contador ÚNICO de antes do
    // fix, o `inscricao.registrada` alheio descartava o snapshot INTEIRO e `oradorAtual` ficava preso
    // em "vAoVivo" (o valor de ANTES do fetch) em vez de avançar para o que o snapshot de fato diz.
    expect(resultado.oradorAtual?.oradorId).toBe("vSnapshot");
    // mas a fila de inscritos É descartada (o snapshot de T0 não sabe da inscrição nova de T1)
    expect(resultado.inscritos).toEqual(comInscricaoAlheia.inscritos);
  });

  it("T5 — a borda falhando (rede/403/500/parse) não muda nada: não há status próprio para degradar", () => {
    const comFala = aplicarEvento(aberta(), falaIniciada(1, "vAoVivo"));
    expect(falharTribuna(comFala)).toEqual(comFala);
    expect(falharTribuna(aberta())).toEqual(aberta());
  });

  it("T7 (I1) — a fila de inscritos NÃO é reordenada: preserva (fase, ordem) do servidor mesmo com `ordem` repetido entre fases", () => {
    // Fila real: expediente = [Ana(1), Carla(2)], ordem_do_dia = [Bruno(1), Davi(2)] — `ordem` é
    // max+1 POR (sessão, fase) (migration …032), então duas fases têm, cada uma, seu próprio 1, 2…
    // O servidor já entrega ordenado por (fase, ordem); um sort por `ordem` sozinho é ESTÁVEL e
    // intercalaria as fases: [Ana(1), Bruno(1), Carla(2), Davi(2)] — ordinais repetidos no telão.
    const doisFluxos = snap({
      inscritos: [
        { inscricaoId: "ana", vereadorId: "vAna", origemInscricao: "pre_sessao_app", fase: "expediente", ordem: 1 },
        { inscricaoId: "carla", vereadorId: "vCarla", origemInscricao: "pre_sessao_app", fase: "expediente", ordem: 2 },
        { inscricaoId: "bruno", vereadorId: "vBruno", origemInscricao: "pre_sessao_app", fase: "ordem_do_dia", ordem: 1 },
        { inscricaoId: "davi", vereadorId: "vDavi", origemInscricao: "pre_sessao_app", fase: "ordem_do_dia", ordem: 2 },
      ],
    });
    const e = hidratarTribuna(aberta(), doisFluxos, seq0);
    expect(e.inscritos.map((i) => i.inscricaoId)).toEqual(["ana", "carla", "bruno", "davi"]);
  });

  it("T8 (A1/N1) — a fila hidratada NÃO se reembaralha quando o PRIMEIRO evento ao vivo chega pelo SSE", () => {
    // O defeito que sobreviveu ao Fix round 1: `lerInscritosTribuna` (HTTP) parou de reordenar, mas o
    // `case "inscricao.registrada"` (SSE) continuava ordenando só por `ordem` — os dois caminhos de
    // escrita do MESMO campo discordavam. Fila real de duas fases, hidratada corretamente pelo HTTP:
    // expediente = [Ana(1), Carla(2)], ordem_do_dia = [Bruno(1), Davi(2)].
    const hidratada = hidratarTribuna(
      aberta(),
      snap({
        inscritos: [
          { inscricaoId: "ana", vereadorId: "vAna", origemInscricao: "pre_sessao_app", fase: "expediente", ordem: 1 },
          { inscricaoId: "carla", vereadorId: "vCarla", origemInscricao: "pre_sessao_app", fase: "expediente", ordem: 2 },
          { inscricaoId: "bruno", vereadorId: "vBruno", origemInscricao: "pre_sessao_app", fase: "ordem_do_dia", ordem: 1 },
          { inscricaoId: "davi", vereadorId: "vDavi", origemInscricao: "pre_sessao_app", fase: "ordem_do_dia", ordem: 2 },
        ],
      }),
      seq0,
    );
    expect(hidratada.inscritos.map((i) => i.inscricaoId)).toEqual(["ana", "carla", "bruno", "davi"]);

    // chega UMA inscrição nova pelo SSE, no EXPEDIENTE (fase que já tem gente na fila) — com o `case`
    // ordenando só por `ordem` (o defeito de N1), o resultado intercalaria por `ordem` sozinho:
    // [ana(1), bruno(1), carla(2), davi(2), elena(3)] — a mesma intercalação que I1 já descrevia,
    // agora publicada na PRIMEIRA inscrição ao vivo depois de uma hidratação correta.
    const comElena = aplicarEvento(hidratada, {
      tipo: "inscricao.registrada",
      seq: 1,
      dados: { "inscricao-id": "elena", "sessao-id": "s1", "vereador-id": "vElena", "origem-inscricao": "pre_sessao_app", fase: "expediente", ordem: 3 },
    });
    // (fase, ordem) preservado: elena entra DEPOIS de carla (mesma fase, ordem maior) e ANTES de bruno/davi
    // (fase textualmente maior — "ordem_do_dia" > "expediente" — que é o que o servidor também usa, já que
    // `fase` é coluna `text`/CHECK, não enum: ver `compararInscritos`).
    expect(comElena.inscritos.map((i) => i.inscricaoId)).toEqual(["ana", "carla", "elena", "bruno", "davi"]);
  });

  describe("os dois contadores de precedência (I4) — cada um dos 5 tipos tem prova própria", () => {
    it("fala.iniciada avança SÓ falaEventoSeq", () => {
      const antes = aberta();
      const depois = aplicarEvento(antes, falaIniciada(1, "v1"));
      expect(depois.falaEventoSeq).toBe(antes.falaEventoSeq + 1);
      expect(depois.inscricaoEventoSeq).toBe(antes.inscricaoEventoSeq);
    });

    it("fala.cronometro avança SÓ falaEventoSeq — inclusive quando é NO-OP visível (guard de fala não-corrente)", () => {
      const antes = aplicarEvento(aberta(), falaIniciada(1, "vAoVivo")); // fala corrente = f1
      const evento: EventoPlenario = {
        tipo: "fala.cronometro",
        seq: 2,
        dados: { "fala-id": "OUTRA-FALA", "sessao-id": "s1", tipo: "pausada", "ocorrido-em": "2026-09-07T22:01:00Z", "segundos-adicionais": null },
      };
      const depois = aplicarEvento(antes, evento);
      expect(depois.marcosCronometro).toEqual(antes.marcosCronometro); // NO-OP visível: marco de fala alheia é ignorado
      expect(depois.falaEventoSeq).toBe(antes.falaEventoSeq + 1); // mas o contador avança do mesmo jeito
      expect(depois.inscricaoEventoSeq).toBe(antes.inscricaoEventoSeq);
    });

    it("fala.encerrada avança SÓ falaEventoSeq", () => {
      const antes = aplicarEvento(aberta(), falaIniciada(1, "vAoVivo"));
      const depois = aplicarEvento(antes, falaEncerrada(2));
      expect(depois.falaEventoSeq).toBe(antes.falaEventoSeq + 1);
      expect(depois.inscricaoEventoSeq).toBe(antes.inscricaoEventoSeq);
    });

    it("inscricao.registrada avança SÓ inscricaoEventoSeq", () => {
      const antes = aberta();
      const depois = aplicarEvento(antes, {
        tipo: "inscricao.registrada",
        seq: 1,
        dados: { "inscricao-id": "i1", "sessao-id": "s1", "vereador-id": "v1", "origem-inscricao": "pre_sessao_app", fase: "ordem_do_dia", ordem: 1 },
      });
      expect(depois.inscricaoEventoSeq).toBe(antes.inscricaoEventoSeq + 1);
      expect(depois.falaEventoSeq).toBe(antes.falaEventoSeq);
    });

    it("inscricao.desistida avança SÓ inscricaoEventoSeq", () => {
      const antes = aberta();
      const depois = aplicarEvento(antes, { tipo: "inscricao.desistida", seq: 1, dados: { "inscricao-id": "i1", "sessao-id": "s1" } });
      expect(depois.inscricaoEventoSeq).toBe(antes.inscricaoEventoSeq + 1);
      expect(depois.falaEventoSeq).toBe(antes.falaEventoSeq);
    });

    it("presenca.registrada não avança NENHUM dos dois contadores", () => {
      const depois = aplicarEvento(aberta(), {
        tipo: "presenca.registrada",
        seq: 1,
        dados: { "sessao-id": "s1", "vereador-id": "v1", tipo: "entrada", modalidade: "plenario", fonte: "manual_secretaria", "ocorrido-em": "2026-09-07T22:00:00Z" },
      } as never);
      expect(depois.falaEventoSeq).toBe(0);
      expect(depois.inscricaoEventoSeq).toBe(0);
    });
  });
});
