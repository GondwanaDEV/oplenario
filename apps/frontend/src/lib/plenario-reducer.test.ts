import { describe, it, expect } from "vitest";
import { estadoInicial, aplicarEvento, hidratarQuorum, falharQuorum, numeroDoTelao, vistaDoQuorum, type EstadoPlenario } from "./plenario-reducer";
import { derivarMeuVoto } from "./meu-voto-vista";
import type { EventoPlenario, SessaoOut } from "./contrato";
import type { QuorumSessaoOut } from "./contrato-sessoes.gen";

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
