import { describe, expect, it } from "vitest";
import {
  derivarAutoria,
  derivarIdentidade,
  derivarPerfil,
  derivarPresenca,
  derivarVotos,
  planificarFrase,
  textosDePresenca,
} from "./perfil-vereador-vista";
import { formatarDataSimples } from "./formatar-data";
import type { PerfilVereadorOut, PresencaOut } from "./contrato-portal.gen";

// Onda E fatia 2 (Task 5) — view-model puro do PERFIL PÚBLICO DO VEREADOR. Estes testes não são cobertura
// de rotina: `docs/14-nota-metodologia-presenca.md` é texto PUBLICADO e o §9 dele impõe regras DURAS
// (nunca percentual; "janela-de-exercicio-conhecida" lido antes dos inteiros; o rótulo do §1 palavra por
// palavra; proibido "sessões realizadas"/"esteve presente"). Como toda a copy vive em constantes deste
// módulo e a fração só existe dentro do ramo "fracao" da união discriminada, cada regra dura vira um
// invariante checável aqui — em vez de depender de alguém reler o JSX. A página é PÚBLICA e NOMINAL: um
// número certo com o rótulo errado é afirmação sobre a conduta de uma pessoa identificada.
//
// Fixtures em camelCase de propósito: a entrada deste módulo é o wire JÁ camelizado por `camelizarChaves`
// (quem fala kebab-case é o teste do hook, que exercita o boundary).

const DATA_PROJECAO = "2026-07-20";

const presencaBase: PresencaOut = {
  sessoesPresente: 8,
  sessoesComChamada: 12,
  janelaDeExercicioConhecida: true,
  janelaAnteriorAProjecao: false,
};

// Os quatro estados do §2 da nota MAIS as duas combinações que a revisão adversarial cobrou: o 0/0 de
// mandato inteiramente anterior à projeção (que publicava "0 de 0" — achado L1-1) e a combinação
// conhecida=false + anterior=true, que hoje é inalcançável porque os dois booleanos derivam da MESMA lista
// `janelas` no adapter, mas essa garantia mora em OUTRO módulo e não é contrato do wire (achado T5-11).
// Toda varredura de invariante roda sobre a matriz inteira, não sobre o caminho feliz.
const ESTADOS_DE_PRESENCA: PresencaOut[] = [
  { ...presencaBase, janelaDeExercicioConhecida: false },
  { ...presencaBase, janelaDeExercicioConhecida: false, janelaAnteriorAProjecao: true },
  { ...presencaBase, janelaAnteriorAProjecao: true },
  { ...presencaBase, sessoesPresente: 0, sessoesComChamada: 0 },
  { ...presencaBase, sessoesPresente: 0, sessoesComChamada: 0, janelaAnteriorAProjecao: true },
  { ...presencaBase, sessoesPresente: 0, sessoesComChamada: 0, janelaDeExercicioConhecida: false },
  presencaBase,
];

const perfilBase: PerfilVereadorOut = {
  vereadorId: "11111111-2222-3333-4444-555555555555",
  nomeParlamentar: "Helena Past",
  nomeCivil: "Helena Pastore Matos",
  legislatura: { numero: 19, anoInicio: 2025, anoFim: 2028 },
  cargoMesa: "2ª Secretária da Mesa",
  comissoes: ["Comissão de Meio Ambiente", "Mesa Diretora"],
  materias: [
    {
      proposicaoId: "p1",
      tipo: "projeto_lei",
      ano: 2026,
      sequencial: 42,
      ementa: "Hortas comunitárias em terrenos públicos",
      estado: "em_comissoes",
    },
  ],
  materiasTotal: 1,
  normasDeAutoria: 3,
  votos: [
    {
      votacaoId: "vt1",
      voto: "sim",
      ocorridoEm: "2026-05-18T14:00:00Z",
      // valor REAL do wire, não o já formatado: `adapters/out/parlamentar.clj` monta
      // `(str tipo " " sequencial "/" ano)` com o tipo CRU do domínio e sem zero-padding — provado pelo
      // teste de integração `http_perfil_test.clj` ("projeto_lei 1/2026"). Uma fixture com "PL 022/2026"
      // é vocabulário fictício e mantinha o defeito de exibição invisível (achado L2-1).
      materiaRotulo: "projeto_lei 22/2026",
      materiaEmenta: "Incentivo à energia solar no município",
    },
  ],
  votosTotal: 1,
  presenca: presencaBase,
  // As DUAS constantes de deploy com valores DIFERENTES de propósito (achado T5-05): iguais, uma trocada
  // pela outra em `derivarPerfil` passava despercebida, e é o único ponto que fia as duas.
  acervoComEloDeAutoriaDesde: "2025-03-04",
  presencaProjetadaDesde: "2026-07-20",
};

/** Normaliza para a varredura de rótulo proibido: sem acento, minúsculo. O §9 proíbe a FRASE, não a
 *  grafia — "Sessões Realizadas" e "sessoes realizadas" são a mesma violação. */
function semAcento(s: string): string {
  return s
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "") // classe = U+0300–U+036F (marcas diacríticas combinantes)
    .toLowerCase();
}

describe("derivarPresenca", () => {
  it("janela desconhecida -> estado 'sem-janela' com o bloco literal do §3", () => {
    const v = derivarPresenca({ ...presencaBase, janelaDeExercicioConhecida: false }, DATA_PROJECAO);
    expect(v.tipo).toBe("sem-janela");
    if (v.tipo !== "sem-janela") throw new Error("ramo errado");
    expect(v.titulo).toBe("Período de exercício não informado.");
    expect(v.texto).toBe(
      "Esta Casa ainda não registrou o período de mandato deste vereador, e por isso não é possível " +
        "calcular presença de forma justa. O registro de presença de cada sessão continua disponível na ata " +
        "correspondente.",
    );
  });

  it("[INV-3] janela desconhecida ignora os inteiros e nunca vira fração, mesmo com números plausíveis", () => {
    const v = derivarPresenca(
      {
        sessoesPresente: 7,
        sessoesComChamada: 9,
        janelaDeExercicioConhecida: false,
        janelaAnteriorAProjecao: false,
      },
      DATA_PROJECAO,
    );
    expect(v.tipo).toBe("sem-janela");
  });

  it("[INV-4] estado sem janela não vaza os inteiros para a tela", () => {
    const v = derivarPresenca(
      {
        sessoesPresente: 7,
        sessoesComChamada: 9,
        janelaDeExercicioConhecida: false,
        janelaAnteriorAProjecao: false,
      },
      DATA_PROJECAO,
    );
    if (v.tipo !== "sem-janela") throw new Error("ramo errado");
    expect(v.titulo).not.toContain("7");
    expect(v.titulo).not.toContain("9");
    expect(v.texto).not.toContain("7");
    expect(v.texto).not.toContain("9");
    expect("presente" in v).toBe(false);
    expect("total" in v).toBe(false);
  });

  it("[INV-5] sem período de exercício nunca publica 0% nem 0 de 0", () => {
    const v = derivarPresenca({ ...presencaBase, janelaDeExercicioConhecida: false }, DATA_PROJECAO);
    if (v.tipo !== "sem-janela") throw new Error("ramo errado");
    // `marco` fica FORA da varredura: ele carrega a data do registro eletrônico, que tem dígitos
    // legitimamente. O que não pode ter dígito é a afirmação sobre a pessoa.
    const afirmacao = `${v.titulo} ${v.texto}`;
    expect(afirmacao).not.toMatch(/\d/);
    expect(afirmacao).not.toContain("%");
  });

  it("[INV-8] mandato inteiramente anterior à projeção não vira 'ainda não houve sessão' NEM '0 de 0'", () => {
    // O ex-vereador de mandato encerrado antes do registro eletrônico responde 0/0 com
    // `janela-de-exercicio-conhecida = true` (é o caso que o campo `janela-anterior-a-projecao` existe para
    // marcar — `adapters/out/parlamentar.clj`). As DUAS leituras prontas da nota são falsas aqui: o §2 4º
    // caso proíbe ler como "ainda não houve sessão neste mandato", e a linha 2 da tabela do §2 proíbe "0 de
    // 0" cru sempre que `sessoes-com-chamada = 0`. Por isso o texto próprio — e NUNCA a fração.
    const v = derivarPresenca(
      {
        sessoesPresente: 0,
        sessoesComChamada: 0,
        janelaDeExercicioConhecida: true,
        janelaAnteriorAProjecao: true,
      },
      DATA_PROJECAO,
    );
    expect(v.tipo).toBe("sem-sessao");
    if (v.tipo !== "sem-sessao") throw new Error("ramo errado");
    expect(v.texto).toBe(
      "O período de exercício deste mandato é anterior ao início do registro eletrônico de presença. " +
        "Nenhuma sessão com registro de presença cai dentro do período publicado, e por isso não há número " +
        "a exibir.",
    );
    expect(v.texto).not.toBe("Ainda não houve sessão com registro de presença neste mandato.");
    expect("presente" in v).toBe(false);
    expect("total" in v).toBe(false);
  });

  it("[INV-13] o booleano manda: conhecida=false vence o denominador zero e a ressalva (§9, ordem 1º)", () => {
    // §9: "`janela-de-exercicio-conhecida` é lido antes dos inteiros, SEMPRE". Nenhuma fixture cruzava
    // conhecida=false com os outros dois sinais, então trocar o 1º `if` de lugar passava verde (T5-11).
    for (const anterior of [true, false]) {
      const v = derivarPresenca(
        {
          sessoesPresente: 0,
          sessoesComChamada: 0,
          janelaDeExercicioConhecida: false,
          janelaAnteriorAProjecao: anterior,
        },
        DATA_PROJECAO,
      );
      expect(v.tipo).toBe("sem-janela");
    }
  });

  it("[INV-12] NENHUM estado produz a frase do §1 com denominador zero ('das 0 sessões')", () => {
    // A regra que o achado L1-1 quebrou: a fração só existe com denominador > 0. Varre a matriz inteira
    // (inclusive os 0/0) porque o defeito nasceu de uma ORDEM de `if`, não de uma fórmula.
    for (const p of ESTADOS_DE_PRESENCA) {
      const varredura = textosDePresenca(derivarPresenca(p, DATA_PROJECAO)).join(" ");
      expect(varredura).not.toContain("das 0 sessões");
      expect(varredura).not.toContain("0 de 0");
    }
    for (const anterior of [true, false]) {
      const v = derivarPresenca(
        {
          sessoesPresente: 0,
          sessoesComChamada: 0,
          janelaDeExercicioConhecida: true,
          janelaAnteriorAProjecao: anterior,
        },
        DATA_PROJECAO,
      );
      expect(v.tipo).not.toBe("fracao");
    }
  });

  it("janela anterior à projeção acrescenta a ressalva do §6 SEM substituir a fração", () => {
    const v = derivarPresenca({ ...presencaBase, janelaAnteriorAProjecao: true }, DATA_PROJECAO);
    if (v.tipo !== "fracao") throw new Error("ramo errado");
    expect(v.presente).toBe(8);
    expect(v.total).toBe(12);
    expect(v.ressalva).toBe(
      "Há período de exercício deste mandato anterior aos dados publicados; o número cobre apenas a parte " +
        "coberta pelo registro eletrônico.",
    );
  });

  it("em exercício e sem sessão com chamada -> a frase única do §2, nunca '0 de 0' cru", () => {
    const v = derivarPresenca(
      { ...presencaBase, sessoesPresente: 0, sessoesComChamada: 0 },
      DATA_PROJECAO,
    );
    expect(v.tipo).toBe("sem-sessao");
    if (v.tipo !== "sem-sessao") throw new Error("ramo errado");
    expect(v.texto).toBe("Ainda não houve sessão com registro de presença neste mandato.");
    expect("presente" in v).toBe(false);
  });

  it("[INV-9] faltou a tudo publica a fração 0 de 12 e não some num estado de ausência de dado", () => {
    const v = derivarPresenca(
      {
        sessoesPresente: 0,
        sessoesComChamada: 12,
        janelaDeExercicioConhecida: true,
        janelaAnteriorAProjecao: false,
      },
      DATA_PROJECAO,
    );
    expect(v.tipo).toBe("fracao");
    if (v.tipo !== "fracao") throw new Error("ramo errado");
    expect(v.presente).toBe(0);
    expect(v.total).toBe(12);
    expect(v.ressalva).toBeNull();
  });

  it("fração normal -> frase do §1 sem ressalva", () => {
    const v = derivarPresenca(presencaBase, DATA_PROJECAO);
    if (v.tipo !== "fracao") throw new Error("ramo errado");
    expect(v.ressalva).toBeNull();
    expect(v.frase.filter((t) => t.forte).length).toBe(2);
  });

  it("[INV-6] a frase publicada é a do §1 palavra por palavra", () => {
    const v = derivarPresenca(presencaBase, DATA_PROJECAO);
    if (v.tipo !== "fracao") throw new Error("ramo errado");
    expect(planificarFrase(v.frase)).toBe(
      "Compareceu a 8 das 12 sessões com registro de presença que a Câmara realizou enquanto este " +
        "vereador estava em exercício do mandato, descontados os períodos de licença registrados.",
    );
  });

  it("[INV-1] presença nunca produz percentual, em nenhum dos quatro estados", () => {
    for (const p of ESTADOS_DE_PRESENCA) {
      const v = derivarPresenca(p, DATA_PROJECAO);
      expect(textosDePresenca(v).join(" ")).not.toContain("%");
      expect("percentual" in v).toBe(false);
    }
  });

  it("[INV-7] rótulo proibido 'sessões realizadas'/'esteve presente' não aparece em nenhum estado", () => {
    for (const p of ESTADOS_DE_PRESENCA) {
      const varredura = semAcento(textosDePresenca(derivarPresenca(p, DATA_PROJECAO)).join(" "));
      expect(varredura).not.toContain("sessoes realizadas");
      expect(varredura).not.toContain("esteve presente");
    }
  });

  it("[INV-10] todo estado de presença remete à ata como documento de fé", () => {
    // `toContain("ata")` era TAUTOLÓGICO: "ata" é substring de "data", e o `marco` — que vai nos quatro
    // ramos — termina em "…anteriores a essa data não constam…". Dava para apagar toda a remissão à ata
    // sem o teste acusar. A varredura agora é por PALAVRA isolada, e cada ramo tem a asserção literal do
    // texto que carrega a remissão.
    for (const p of ESTADOS_DE_PRESENCA) {
      const v = derivarPresenca(p, DATA_PROJECAO);
      expect(textosDePresenca(v).join(" ")).toMatch(/\bata\b/);
      if (v.tipo === "sem-janela") {
        expect(v.texto).toContain("disponível na ata correspondente");
      } else {
        expect(v.ata).toBe(
          "O documento de fé é a ata de cada sessão. Este número é um resumo derivado dos registros; " +
            "divergência entre ele e a ata resolve-se pela ata.",
        );
      }
    }
  });

  it("todo estado exibe o marco do registro eletrônico com a data VINDA DA RESPOSTA", () => {
    for (const p of ESTADOS_DE_PRESENCA) {
      const v = derivarPresenca(p, "2026-09-01");
      expect(v.marco).toContain("01/09/2026");
      expect(v.marco).not.toContain("20/07/2026");
    }
  });

  it("[INV-11] presente maior que o total é bug de servidor: renderiza como veio, sem inventar frase de erro", () => {
    // Impossível pelo read-model atual. Este teste TRAVA o comportamento de hoje (render cru) e registra
    // o risco no nome — a frase substituta seria copy nova não decidida, e inventá-la aqui seria pior.
    const v = derivarPresenca(
      {
        sessoesPresente: 13,
        sessoesComChamada: 12,
        janelaDeExercicioConhecida: true,
        janelaAnteriorAProjecao: false,
      },
      DATA_PROJECAO,
    );
    if (v.tipo !== "fracao") throw new Error("ramo errado");
    expect(v.presente).toBe(13);
    expect(v.total).toBe(12);
    expect(planificarFrase(v.frase)).toContain("Compareceu a 13 das 12 sessões");
  });

  it("data date-only não volta um dia em fuso negativo (e formato inesperado sai cru)", () => {
    expect(formatarDataSimples("2026-07-20")).toBe("20/07/2026");
    expect(formatarDataSimples("2026-07-20T00:00:00Z")).toBe("2026-07-20T00:00:00Z");
    expect(formatarDataSimples("")).toBe("");
  });
});

describe("derivarIdentidade", () => {
  it("nomeParlamentar null -> h1 é o nome civil e não há linha secundária (o servidor não faz esse fallback de propósito)", () => {
    const r = derivarIdentidade({ ...perfilBase, nomeParlamentar: null });
    expect(r.nome).toBe("Helena Pastore Matos");
    expect(r.nomeSecundario).toBeNull();
  });

  it("nomeParlamentar vazio ('') conta como ausente (fail-closed)", () => {
    const r = derivarIdentidade({ ...perfilBase, nomeParlamentar: "   " });
    expect(r.nome).toBe("Helena Pastore Matos");
    expect(r.nomeSecundario).toBeNull();
  });

  it("nomeParlamentar igual ao civil -> exibe uma vez só (repetir insinua duas identidades)", () => {
    const r = derivarIdentidade({ ...perfilBase, nomeParlamentar: "Helena Pastore Matos" });
    expect(r.nome).toBe("Helena Pastore Matos");
    expect(r.nomeSecundario).toBeNull();
  });

  it("nomeParlamentar diferente do civil -> exibe os dois", () => {
    const r = derivarIdentidade(perfilBase);
    expect(r.nome).toBe("Helena Past");
    expect(r.nomeSecundario).toBe("Helena Pastore Matos");
  });

  it("legislatura null -> papel null (nunca 'sem legislatura', nunca a legislatura vigente da Casa)", () => {
    const r = derivarIdentidade({ ...perfilBase, legislatura: null });
    expect(r.papel).toBeNull();
  });

  it("legislatura presente -> rótulo montado no cliente com travessão", () => {
    const r = derivarIdentidade(perfilBase);
    expect(r.papel).toBe("19ª Legislatura (2025–2028)");
  });

  it("cargoMesa null -> some; valor livre sai cru", () => {
    expect(derivarIdentidade({ ...perfilBase, cargoMesa: null }).cargoMesa).toBeNull();
    expect(derivarIdentidade({ ...perfilBase, cargoMesa: "Vice-Presidenta" }).cargoMesa).toBe(
      "Vice-Presidenta",
    );
  });

  it("comissões vazias -> texto que explica, nunca 'não participa de comissões'", () => {
    const r = derivarIdentidade({ ...perfilBase, comissoes: [] });
    expect(r.comissoes).toEqual([]);
    expect(r.comissoesVazio).toBe("Não há comissões registradas para este vereador.");
    expect(semAcento(r.comissoesVazio ?? "")).not.toContain("nao participa");
  });

  it("a vista não expõe contador de comissões (o contrato não distingue a Mesa e contá-la duplicaria)", () => {
    const r = derivarIdentidade(perfilBase);
    expect(r.comissoes).toEqual(["Comissão de Meio Ambiente", "Mesa Diretora"]);
    expect(r.comissoesVazio).toBeNull();
    expect("comissoesTotal" in r).toBe(false);
  });

  it("iniciais vêm do nome exibido, nunca do vereadorId", () => {
    expect(derivarIdentidade(perfilBase).iniciais).toBe("HP");
    expect(derivarIdentidade({ ...perfilBase, nomeParlamentar: "Helena" }).iniciais).toBe("H");
    expect(derivarIdentidade(perfilBase).iniciais).not.toContain("1");
  });

  it("nome com 3+ palavras respeita o teto de 2 caracteres (primeira e última), que é o caso NORMAL", () => {
    // Nome civil brasileiro tem 4-5 palavras; a caixa do avatar (.perfil-foto, 84px) é dimensionada para
    // dois caracteres. Sem este caso, `palavras.map(w => w[0]).join("")` passava verde e "José Carlos da
    // Silva Nogueira" sairia como "JCDSN" (achado T5-09).
    expect(derivarIdentidade({ ...perfilBase, nomeParlamentar: null }).iniciais).toBe("HM");
    const longo = derivarIdentidade({
      ...perfilBase,
      nomeParlamentar: null,
      nomeCivil: "José Carlos da Silva Nogueira",
    });
    expect(longo.iniciais).toBe("JN");
    expect(longo.iniciais.length).toBeLessThanOrEqual(2);
  });

  it("nome de comissão repetido não duplica o chip (o SQL de origem não faz DISTINCT)", () => {
    // `cadastros/db/comissao.clj` faz LEFT JOIN com `comissao_cargo` SEM `DISTINCT` e não há UNIQUE em
    // (ente_id, comissao_id, vereador_id): dois cargos vigentes na mesma comissão multiplicam a linha e o
    // adapter faz `(mapv :nome ...)` cru. Deduplicar é decisão de APRESENTAÇÃO e mora aqui (achado L2-3).
    const r = derivarIdentidade({
      ...perfilBase,
      comissoes: ["Comissão de Justiça", "Comissão de Justiça", "Comissão de Finanças"],
    });
    expect(r.comissoes).toEqual(["Comissão de Justiça", "Comissão de Finanças"]);
  });

  it("o grupo de chips tem rótulo próprio — a relação 'isto é cargo + comissões' não pode viver só na diagramação", () => {
    // WCAG 1.3.1: a fileira de pílulas é percebida como grupo visualmente; sem rótulo, o leitor de tela
    // recebe quatro itens de naturezas diferentes numa sequência plana (achado A11Y-2). A copy nasce aqui,
    // não no JSX.
    expect(derivarIdentidade(perfilBase).comissoesRotulo).toBe("Cargo na Mesa e comissões");
  });
});

describe("derivarAutoria", () => {
  it("lista vazia -> texto de vazio + a declaração de recorte do acervo continua visível", () => {
    const r = derivarAutoria({ ...perfilBase, materias: [], materiasTotal: 0 }, "fortaleza");
    expect(r.linhas).toEqual([]);
    expect(r.vazio).toBe("Nenhuma matéria de autoria consta desta lista.");
    expect(r.recorteAcervo).toContain("04/03/2025");
    expect(r.truncamento).toBeNull();
  });

  it("declaração de recorte do acervo aparece SEMPRE, com a data vinda da resposta", () => {
    const r = derivarAutoria({ ...perfilBase, acervoComEloDeAutoriaDesde: "2024-11-30" }, "fortaleza");
    expect(r.recorteAcervo).toBe(
      "Matérias de autoria estão publicadas a partir de 30/11/2024. Matérias protocoladas antes dessa " +
        "data não constam desta lista.",
    );
    expect(r.vazio).toBeNull();
  });

  it("os CONTADORES ganham declaração de recorte própria, adjacente a eles (não só a lista)", () => {
    // `contar-por-autor`/`contar-normas-por-autor` filtram por `autor_id IS NOT NULL` — o MESMO corte da
    // lista, e sem replay na mig 0063. Um vereador de 5º mandato publica "0 · matérias de autoria" e
    // "0 · viraram lei" em 28px; a única frase que relativizava isso falava "desta lista" e ficava duas
    // seções abaixo dos cards (achado L1-3).
    const r = derivarAutoria(perfilBase, "fortaleza");
    expect(r.recorteNumeros).toBe(
      "Os números de matérias de autoria e de leis cobrem apenas o acervo publicado a partir de " +
        "04/03/2025: matérias protocoladas antes dessa data não entram nestes totais.",
    );
  });

  it("materiasTotal maior que a lista -> truncamento que descreve a ORDEM REAL, nunca 'mais recentes'", () => {
    // `transparencia/db/materia.clj` ordena por (ano DESC, sequencial DESC) e o próprio módulo dono
    // documenta: "dizer 'mais recentes primeiro' seria falso" — `sequencial` é contador POR ESPÉCIE, então
    // um requerimento de fevereiro vem antes de um PL de novembro (achado L1-2).
    const r = derivarAutoria({ ...perfilBase, materiasTotal: 137 }, "fortaleza");
    expect(r.truncamento).toBe(
      "Mostrando 1 de 137 matérias, da numeração mais alta para a mais baixa.",
    );
    expect(r.truncamento).not.toMatch(/recente/i);
    expect(r.materiasTotal).toBe(137);
  });

  it("materiasTotal igual à lista -> sem linha de truncamento", () => {
    expect(derivarAutoria(perfilBase, "fortaleza").truncamento).toBeNull();
  });

  it("ref e faixa de tramitação reusam derivarRef/derivarTramitacao (estado desconhecido não lança)", () => {
    const r = derivarAutoria(perfilBase, "fortaleza");
    expect(r.linhas[0].ref).toBe("PL 042/2026");
    expect(r.linhas[0].estagios.length).toBe(5);
    expect(r.linhas[0].rotuloAria).toBe(
      "Tramitação de PL 042/2026: concluídos Protocolo; atual Comissões; pendente 1º turno, 2º turno, Sanção.",
    );

    const exotico = derivarAutoria(
      { ...perfilBase, materias: [{ ...perfilBase.materias[0], estado: "vocabulario_de_tenant" }] },
      "fortaleza",
    );
    expect(exotico.linhas[0].estagios).toEqual([{ rotulo: "Protocolo", situacao: "ativo" }]);
  });

  it("href aponta para a ficha pública da matéria no mesmo ente", () => {
    const r = derivarAutoria(perfilBase, "casa/estranha");
    expect(r.linhas[0].href).toBe("/portal/casa/casa%2Festranha/materias/p1");
  });
});

describe("derivarVotos", () => {
  it("votos vazios -> texto honesto, sem mencionar voto secreto como omissão", () => {
    const r = derivarVotos({ ...perfilBase, votos: [], votosTotal: 0 });
    expect(r.linhas).toEqual([]);
    expect(r.vazio).toBe("Ainda não há votos nominais registrados para este vereador.");
    expect(semAcento(r.vazio ?? "")).not.toContain("secret");
    expect(r.truncamento).toBeNull();
  });

  it("votosTotal maior que a lista -> linha de truncamento 'mostrando 50 de N'", () => {
    const cinquenta = Array.from({ length: 50 }, (_, i) => ({
      votacaoId: `vt${i}`,
      voto: "sim",
      ocorridoEm: `2026-05-${String((i % 28) + 1).padStart(2, "0")}T14:00:00Z`,
      materiaRotulo: "PL 022/2026",
      materiaEmenta: null,
    }));
    const r = derivarVotos({ ...perfilBase, votos: cinquenta, votosTotal: 214 });
    expect(r.truncamento).toBe("Mostrando os 50 votos mais recentes, de 214 no total.");
  });

  it("materiaRotulo null -> a linha do voto aparece com 'voto em matéria não publicada', nunca é omitida", () => {
    const r = derivarVotos({
      ...perfilBase,
      votos: [{ ...perfilBase.votos[0], materiaRotulo: null, materiaEmenta: null }],
    });
    expect(r.linhas.length).toBe(1);
    expect(r.linhas[0].rotulo).toBe("voto em matéria não publicada");
    expect(r.linhas[0].ementa).toBeNull();
  });

  it("voto sem matéria projetada não repete o rótulo de fallback: a sublinha vira só a data", () => {
    // `materia-rotulo` e `materia-ementa` vêm do MESMO LEFT JOIN, então caem nulos JUNTOS. Com título
    // `ementa ?? rotulo` e sublinha `rotulo · quando`, o fallback saía impresso duas vezes na mesma linha
    // (achados L2-2 / A11Y-5 / L6-3). O par título+sublinha é resolvido aqui, não no JSX.
    const semMateria = derivarVotos({
      ...perfilBase,
      votos: [{ ...perfilBase.votos[0], materiaRotulo: null, materiaEmenta: null }],
    }).linhas[0];
    expect(semMateria.subtitulo).toBe("18/05/2026");
    expect(semMateria.subtitulo).not.toContain("voto em matéria não publicada");

    const comMateria = derivarVotos(perfilBase).linhas[0];
    expect(comMateria.subtitulo).toBe("PL 022/2026 · 18/05/2026");
  });

  it("o rótulo da matéria votada sai na MESMA grafia da lista de autoria (o wire manda o enum cru)", () => {
    // O backend monta `(str tipo " " sequencial "/" ano)` — "projeto_lei 22/2026", tipo cru de banco e sem
    // zero-padding. Publicado assim, a mesma proposição aparecia com duas grafias na mesma página (achado
    // L2-1). Fail-closed: formato inesperado sai CRU, nunca lança.
    expect(derivarVotos(perfilBase).linhas[0].rotulo).toBe("PL 022/2026");
    const exotico = derivarVotos({
      ...perfilBase,
      votos: [
        { ...perfilBase.votos[0], votacaoId: "a", materiaRotulo: "requerimento 7/2026" },
        { ...perfilBase.votos[0], votacaoId: "b", materiaRotulo: "vocabulario_de_tenant 3/2026" },
        { ...perfilBase.votos[0], votacaoId: "c", materiaRotulo: "forma inesperada" },
      ],
      votosTotal: 3,
    });
    const por = Object.fromEntries(exotico.linhas.map((l) => [l.votacaoId, l.rotulo]));
    expect(por.a).toBe("REQ 007/2026");
    expect(por.b).toBe("VOCABULARIO_DE_TENANT 003/2026");
    expect(por.c).toBe("forma inesperada");
  });

  it("voto fora do vocabulário sim/nao/abstencao -> rótulo cru e chip neutro (fail-closed, não lança)", () => {
    const r = derivarVotos({
      ...perfilBase,
      votos: [
        { ...perfilBase.votos[0], votacaoId: "a", voto: "sim" },
        { ...perfilBase.votos[0], votacaoId: "b", voto: "nao" },
        { ...perfilBase.votos[0], votacaoId: "c", voto: "abstencao" },
        { ...perfilBase.votos[0], votacaoId: "d", voto: "obstrucao_regimental" },
      ],
      votosTotal: 4,
    });
    const por = Object.fromEntries(r.linhas.map((l) => [l.votacaoId, l]));
    expect([por.a.votoRotulo, por.a.votoClasse]).toEqual(["A favor", "chip-ok"]);
    expect([por.b.votoRotulo, por.b.votoClasse]).toEqual(["Contra", "chip-risco"]);
    expect([por.c.votoRotulo, por.c.votoClasse]).toEqual(["Absteve-se", "chip-neutro"]);
    expect([por.d.votoRotulo, por.d.votoClasse]).toEqual(["obstrucao_regimental", "chip-neutro"]);
  });

  it("votos saem ordenados por data decrescente sem mutar o array do wire", () => {
    const votos = [
      { ...perfilBase.votos[0], votacaoId: "antigo", ocorridoEm: "2026-04-29T10:00:00Z" },
      { ...perfilBase.votos[0], votacaoId: "recente", ocorridoEm: "2026-05-18T14:00:00Z" },
      { ...perfilBase.votos[0], votacaoId: "meio", ocorridoEm: "2026-05-06T09:00:00Z" },
    ];
    const entrada = { ...perfilBase, votos, votosTotal: 3 };
    const r = derivarVotos(entrada);
    expect(r.linhas.map((l) => l.votacaoId)).toEqual(["recente", "meio", "antigo"]);
    expect(votos.map((v) => v.votacaoId)).toEqual(["antigo", "recente", "meio"]);
    // VALOR, não só forma (achado T5-10): a regex `^\d{2}\/\d{2}\/\d{4}$` sobrevivia a um deslocamento de
    // um dia ou ao campo errado. 14:00Z fica no mesmo dia civil em qualquer fuso do Brasil.
    // CARRY conhecido: `formatarData` usa o fuso do RUNTIME (Intl sem `timeZone`), então um instante perto
    // da meia-noite UTC rende dias diferentes no container e no navegador do cidadão em America/Fortaleza.
    // Fixar o fuso é decisão de plataforma (kernel/tempo já cravou America/Fortaleza no backend).
    expect(r.linhas.map((l) => l.quando)).toEqual(["18/05/2026", "06/05/2026", "29/04/2026"]);
  });
});

describe("derivarPerfil", () => {
  it("compõe identidade + presença + autoria + votos numa vista só", () => {
    const v = derivarPerfil(perfilBase, "fortaleza");
    expect(v.identidade.nome).toBe("Helena Past");
    expect(v.presenca.tipo).toBe("fracao");
    expect(v.autoria.linhas.length).toBe(1);
    expect(v.votos.linhas.length).toBe(1);
    expect(v.autoria.normasDeAutoria).toBe(3);
    expect(v.autoria.votosTotal).toBe(1);
  });

  it("cada bloco recebe a SUA constante de deploy — presença não come a data do acervo, nem o contrário", () => {
    // As duas são strings independentes no contrato e podem divergir (um backfill de acervo move só uma).
    // `derivarPerfil` é o ÚNICO ponto que as fia, e trocar uma pela outra passava verde (achado T5-05).
    const v = derivarPerfil(perfilBase, "fortaleza");
    expect(v.presenca.marco).toContain("20/07/2026");
    expect(v.presenca.marco).not.toContain("04/03/2025");
    expect(v.autoria.recorteAcervo).toContain("04/03/2025");
    expect(v.autoria.recorteAcervo).not.toContain("20/07/2026");
    expect(v.autoria.recorteNumeros).toContain("04/03/2025");
  });
});
