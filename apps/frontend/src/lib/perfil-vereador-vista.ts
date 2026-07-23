// View-model puro do PERFIL PÚBLICO DO VEREADOR (Onda E fatia 2, Task 5) — GET /portal/casa/{ente}/
// vereadores/{vereadorId}. Espelha ficha-materia-vista.ts/tramitacao-vista.ts: zero React, zero fetch,
// entrada = o tipo wire JÁ camelizado, saída = tipos `*Vista` explícitos, fail-closed em todo vocabulário
// livre.
//
// POR QUE TODA A COPY DE PRESENÇA VIVE AQUI, COMO CONSTANTE: `docs/14-nota-metodologia-presenca.md` é
// texto PUBLICADO, não sugestão — o §9 proíbe parafrasear ("o rótulo é o do §1, no sentido palavra por
// palavra") e proíbe percentual. Com as frases em constantes e a fração existindo SÓ dentro do ramo
// `"fracao"` da união discriminada, as regras duras viram invariantes checáveis por tipo e por teste
// unitário, em vez de depender de alguém reler o JSX. A página é PÚBLICA e NOMINAL: um número certo com
// o rótulo errado é afirmação sobre a conduta de uma pessoa identificada.

import type {
  MateriaDeAutoriaOut,
  PerfilVereadorOut,
  PresencaOut,
  VotoPublicoOut,
} from "./contrato-portal.gen";
import { derivarRef } from "./materia-vista";
import { derivarTramitacao, descreverFaixa, type EstagioTramitacao } from "./tramitacao-vista";
import { formatarData, formatarDataSimples } from "./formatar-data";

/** Um pedaço da frase publicada do §1; `forte` = vai em <b> (é o que separa a afirmação
 *  verificável da qualificação — os dois trechos em negrito são negrito NA NOTA). */
export type TrechoPresenca = { texto: string; forte: boolean };

export type PresencaVista =
  | { tipo: "sem-janela"; titulo: string; texto: string; marco: string }
  | { tipo: "sem-sessao"; texto: string; marco: string; ata: string }
  | {
      tipo: "fracao";
      presente: number;
      total: number;
      frase: TrechoPresenca[];
      ressalva: string | null;
      marco: string;
      ata: string;
    };

export type IdentidadeVista = {
  nome: string; // h1
  nomeSecundario: string | null; // nome civil, SÓ quando difere do parlamentar
  iniciais: string; // avatar
  papel: string | null; // "19ª Legislatura (2025–2028)" ou null
  cargoMesa: string | null;
  comissoes: string[];
  comissoesRotulo: string; // rótulo ACESSÍVEL do grupo de chips (WCAG 1.3.1)
  comissoesVazio: string | null;
};

export type LinhaMateriaVista = {
  proposicaoId: string;
  ref: string; // "PL 042/2026"
  ementa: string;
  estagios: EstagioTramitacao[];
  rotuloAria: string;
  href: string;
};

export type AutoriaVista = {
  linhas: LinhaMateriaVista[];
  materiasTotal: number;
  normasDeAutoria: number;
  votosTotal: number;
  truncamento: string | null;
  vazio: string | null;
  recorteAcervo: string; // SEMPRE presente — qualifica a LISTA
  recorteNumeros: string; // SEMPRE presente — qualifica os CONTADORES, e vai adjacente a eles
};

export type LinhaVotoVista = {
  votacaoId: string;
  voto: string; // valor CRU do wire (sim|nao|abstencao) — chaveia a FORMA do chip, nunca o texto
  rotulo: string; // materiaRotulo normalizado OU "voto em matéria não publicada"
  ementa: string | null;
  quando: string; // dd/mm/aaaa
  subtitulo: string; // "PL 022/2026 · 18/05/2026" — só a data quando o título JÁ é o rótulo
  votoRotulo: string; // "A favor" | "Contra" | "Absteve-se" | valor cru (fail-closed)
  votoClasse: string; // "chip-ok" | "chip-risco" | "chip-neutro"
};

export type VotosVista = {
  linhas: LinhaVotoVista[];
  truncamento: string | null;
  vazio: string | null;
};

export type PerfilVista = {
  identidade: IdentidadeVista;
  presenca: PresencaVista;
  autoria: AutoriaVista;
  votos: VotosVista;
};

// ---- [LITERAL docs/14 §1] a frase publicada. X e Y são os ÚNICOS pontos de substituição.
//      "a Câmara" fica literal — NÃO trocar pelo nome real da Casa (a nota não autoriza).
//      Sem variante de singular e sem flexão de gênero: o §9 proíbe parafrasear, e emendar
//      isso exige emendar a nota primeiro (carry registrado no brief).
const FRASE_A = (presente: number, total: number) =>
  `Compareceu a ${presente} das ${total} sessões com registro de presença`;
const FRASE_B = " que a Câmara realizou ";
const FRASE_C = "enquanto este vereador estava em exercício do mandato";
const FRASE_D = ", descontados os períodos de licença registrados.";

// ---- [LITERAL docs/14 §3] sem período de exercício registrado.
const SEM_JANELA_TITULO = "Período de exercício não informado.";
const SEM_JANELA_TEXTO =
  "Esta Casa ainda não registrou o período de mandato deste vereador, e por isso não é possível " +
  "calcular presença de forma justa. O registro de presença de cada sessão continua disponível na ata " +
  "correspondente.";

// ---- [LITERAL docs/14 §2] em exercício, ainda sem sessão com chamada.
const SEM_SESSAO = "Ainda não houve sessão com registro de presença neste mandato.";

// ---- [COPY NOVA, derivada de docs/14 §2 (4º caso) + §6] exercício ANTERIOR ao registro eletrônico E
//      `sessoes-com-chamada = 0` — o ex-vereador de mandato encerrado antes da projeção.
//      Este é o cruzamento em que as duas frases prontas da nota falham, uma de cada lado:
//        • a linha 2 da tabela do §2 proíbe "0 de 0" cru SEMPRE que `sessoes-com-chamada = 0` — e a
//          docstring de `adapters/out/parlamentar.clj` diz, com todas as letras, que sem este sinal "a tela
//          publicaria 'compareceu a 0 de 0' sob o nome de uma pessoa";
//        • o parágrafo do 4º caso proíbe ler o MESMO 0/0 como "está em exercício e ainda não houve sessão"
//          — "ainda não houve" e "neste mandato" descrevem mandato em curso, e este não está.
//      Daí uma terceira frase, que não afirma nem uma nem a outra. NÃO leva a ressalva do §6 junto: aquela
//      fala de "o número", e aqui não há número nenhum — o que ela diria já está dito aqui.
const SEM_SESSAO_ANTERIOR =
  "O período de exercício deste mandato é anterior ao início do registro eletrônico de presença. " +
  "Nenhuma sessão com registro de presença cai dentro do período publicado, e por isso não há número " +
  "a exibir.";

// ---- [LITERAL docs/14 §6] a ressalva. Acompanha a fração, NÃO a substitui.
const RESSALVA_ANTERIOR_A_PROJECAO =
  "Há período de exercício deste mandato anterior aos dados publicados; o número cobre apenas a parte " +
  "coberta pelo registro eletrônico.";

// ---- [LITERAL docs/14 §6, com a data interpolada da resposta — NUNCA cravada no bundle]
const marcoRegistro = (dataFmt: string) =>
  `O registro eletrônico de presença passou a alimentar esta página em ${dataFmt}. Sessões anteriores a ` +
  `essa data não constam — nem no numerador, nem no denominador.`;

// ---- [COPY NOVA, derivada de docs/14 §5.2] a remissão à ata. Nos estados 2/3/4 vai em campo próprio;
//      no estado 1 ela JÁ está dentro do literal do §3, por isso aquele ramo não tem o campo.
const ATA_DOCUMENTO_DE_FE =
  "O documento de fé é a ata de cada sessão. Este número é um resumo derivado dos registros; " +
  "divergência entre ele e a ata resolve-se pela ata.";

// ---- [COPY NOVA] recorte do acervo de autoria. Sempre visível, junto do bloco de autoria.
const recorteAcervoAutoria = (dataFmt: string) =>
  `Matérias de autoria estão publicadas a partir de ${dataFmt}. Matérias protocoladas antes dessa data ` +
  `não constam desta lista.`;

// ---- [COPY NOVA] o MESMO recorte, dito para os CONTADORES. Não é redundância: `contar-por-autor` e
//      `contar-normas-por-autor` filtram por `autor_id IS NOT NULL` exatamente como a listagem, e a
//      migration 0063 não faz replay — um vereador de 5º mandato publica "0 · matérias de autoria" e
//      "0 · viraram lei" em 28px. A frase da lista fala "desta lista" e fica duas seções abaixo dos cards:
//      não alcança os números. Fica de fora o card de votos nominais, cujo total é o universo real.
const recorteNumerosAutoria = (dataFmt: string) =>
  `Os números de matérias de autoria e de leis cobrem apenas o acervo publicado a partir de ${dataFmt}: ` +
  `matérias protocoladas antes dessa data não entram nestes totais.`;

// ---- [COPY NOVA] truncamento — o par lista+total é OBRIGATÓRIO no contrato.
//      A ordem descrita é a ordem REAL do read-model: `transparencia/db/materia.clj` ordena por
//      (ano DESC, sequencial DESC) e o módulo dono documenta que "dizer 'mais recentes primeiro' seria
//      falso" — `sequencial` é contador POR ESPÉCIE, então um requerimento de fevereiro vem antes de um
//      projeto de lei de novembro. Prometer "mais recentes" seria dizer ao leitor que o que ficou fora do
//      teto é mais antigo do que o que aparece, e não é. (Os VOTOS ordenam por `ocorrido_em` de verdade,
//      por isso `truncamentoVotos` pode dizer "mais recentes".)
const truncamentoMaterias = (mostradas: number, total: number) =>
  `Mostrando ${mostradas} de ${total} matérias, da numeração mais alta para a mais baixa.`;
const truncamentoVotos = (mostrados: number, total: number) =>
  `Mostrando os ${mostrados} votos mais recentes, de ${total} no total.`;

// ---- [COPY NOVA] vazios que EXPLICAM (GUIDELINES §2), nunca afirmam conduta.
const VAZIO_MATERIAS = "Nenhuma matéria de autoria consta desta lista.";
const VAZIO_VOTOS = "Ainda não há votos nominais registrados para este vereador.";
const VAZIO_COMISSOES = "Não há comissões registradas para este vereador.";

// ---- [COPY NOVA] rótulo ACESSÍVEL do grupo de chips (cargo na Mesa + comissões). Sem ele, a relação
//      "estes são o cargo e as comissões desta pessoa" existe só na diagramação — WCAG 1.3.1. A assimetria
//      que denunciava a falta: o estado VAZIO ganhava uma frase completa e o estado CHEIO, nada.
const ROTULO_COMISSOES = "Cargo na Mesa e comissões";

// ---- [do próprio contrato] rótulo do voto cuja matéria não foi projetada. A linha NUNCA é omitida.
const VOTO_SEM_MATERIA = "voto em matéria não publicada";

// vocabulário REAL do voto nominal: `sim` | `nao` | `abstencao` (migration 0064 + legislativo/logic).
// Fail-closed: valor fora do trio sai CRU e cai no chip neutro — nunca lança, nunca some a linha.
const VOTO_ROTULO: Record<string, string> = { sim: "A favor", nao: "Contra", abstencao: "Absteve-se" };
const VOTO_CLASSE: Record<string, string> = {
  sim: "chip-ok",
  nao: "chip-risco",
  abstencao: "chip-neutro",
};

/** Resolve o estado de presença. ORDEM DE AVALIAÇÃO É NORMATIVA (docs/14 §9 + §2), primeiro que casa vence:
 *   1º  janelaDeExercicioConhecida — o booleano manda; os inteiros só existem se ele for true (§9: "é lido
 *       antes dos inteiros, sempre"; um nil inesperado já cai em false no adapter).
 *   2º  sessoesComChamada === 0 — DENOMINADOR ZERO NUNCA VIRA FRAÇÃO. É proibição absoluta da linha 2 da
 *       tabela do §2 ("Nunca '0 de 0' cru"), e não é qualificada por `janela-anterior-a-projecao`.
 *   3º  a fração, com a ressalva do §6 quando `janelaAnteriorAProjecao` — a ressalva é ADITIVA (§2, 4ª
 *       linha: "a fração MAIS a ressalva"), nunca seletora de ramo.
 *
 * A REGRESSÃO QUE ISTO FECHA (revisão adversarial, achado L1-1): `janelaAnteriorAProjecao` era testado
 * ANTES do denominador zero e caía direto na fração, sem guarda de zero. O ex-vereador de mandato
 * encerrado antes da projeção — 0/0 com conhecida=true — publicava "0 de 0" e "Compareceu a 0 das 0
 * sessões…" sob o nome de uma pessoa, que é exatamente o que o campo `janela-anterior-a-projecao` foi
 * criado para impedir. E não era caso raro: no dia do deploy (§10) TODO vereador em exercício tem janela
 * iniciada antes de 20/07/2026, então os 21 vereadores da Casa publicariam isso ao mesmo tempo enquanto
 * nenhuma sessão tivesse sido projetada. O que a ordem antiga protegia — não ler o 0/0 anterior como
 * "ainda não houve sessão neste mandato" — continua protegido, agora pelo TEXTO do ramo (SEM_SESSAO_
 * ANTERIOR), não pela ordem dos `if`. */
export function derivarPresenca(p: PresencaOut, presencaProjetadaDesde: string): PresencaVista {
  const marco = marcoRegistro(formatarDataSimples(presencaProjetadaDesde));

  if (!p.janelaDeExercicioConhecida) {
    return { tipo: "sem-janela", titulo: SEM_JANELA_TITULO, texto: SEM_JANELA_TEXTO, marco };
  }
  if (p.sessoesComChamada === 0) {
    return {
      tipo: "sem-sessao",
      texto: p.janelaAnteriorAProjecao ? SEM_SESSAO_ANTERIOR : SEM_SESSAO,
      marco,
      ata: ATA_DOCUMENTO_DE_FE,
    };
  }
  return fracao(p, p.janelaAnteriorAProjecao ? RESSALVA_ANTERIOR_A_PROJECAO : null, marco);
}

/** SÓ é chamada com `sessoesComChamada > 0` — o guarda está no único caller, logo acima, e o [INV-12] o
 *  trava. `FRASE_A` nunca pode ser construída com total = 0. */
function fracao(p: PresencaOut, ressalva: string | null, marco: string): PresencaVista {
  return {
    tipo: "fracao",
    presente: p.sessoesPresente,
    total: p.sessoesComChamada,
    frase: [
      { texto: FRASE_A(p.sessoesPresente, p.sessoesComChamada), forte: true },
      { texto: FRASE_B, forte: false },
      { texto: FRASE_C, forte: true },
      { texto: FRASE_D, forte: false },
    ],
    ressalva,
    marco,
    ata: ATA_DOCUMENTO_DE_FE,
  };
}

/** Concatena os trechos da frase publicada. Existe para o teste comparar contra o §1 palavra por palavra
 *  e para renderizar o `aria-label` do bloco sem perder o texto dos trechos em negrito. */
export function planificarFrase(trechos: TrechoPresenca[]): string {
  return trechos.map((t) => t.texto).join("");
}

/** TODOS os textos que o estado leva à tela, em qualquer ramo. Superfície única sobre a qual os testes de
 *  proibição (percentual, "sessões realizadas", "esteve presente") varrem — sem isto o teste só cobriria o
 *  caminho feliz, que é justamente o que não erra. */
export function textosDePresenca(v: PresencaVista): string[] {
  if (v.tipo === "sem-janela") return [v.titulo, v.texto, v.marco];
  if (v.tipo === "sem-sessao") return [v.texto, v.marco, v.ata];
  return [planificarFrase(v.frase), v.ressalva ?? "", v.marco, v.ata];
}

/** Iniciais do NOME EXIBIDO (nunca do vereadorId — um UUID no avatar é identificação de fallback, e o §7
 *  do brief proíbe): 1ª letra da primeira e da última palavra, no máximo 2 chars. */
function derivarIniciais(nome: string): string {
  const palavras = nome.trim().split(/\s+/).filter(Boolean);
  if (palavras.length === 0) return "";
  const primeira = palavras[0][0] ?? "";
  const ultima = palavras.length > 1 ? (palavras[palavras.length - 1][0] ?? "") : "";
  return `${primeira}${ultima}`.toUpperCase();
}

export function derivarIdentidade(p: PerfilVereadorOut): IdentidadeVista {
  // `||` e não `??` de propósito: apelido em branco ("" ou só espaço) conta como AUSENTE. O fallback é
  // decisão da UI — o servidor faz pass-through cru do que a Casa cadastrou, também de propósito.
  const apelido = p.nomeParlamentar?.trim() || null;
  const nome = apelido || p.nomeCivil;
  // iguais não repetem: exibir o mesmo nome duas vezes insinua duas identidades onde há uma.
  const nomeSecundario = apelido && apelido !== p.nomeCivil ? p.nomeCivil : null;
  // Travessão U+2013, como no exemplo da docstring do wire. `legislatura === null` NÃO implica
  // `janelaDeExercicioConhecida === false` — são sinais independentes (ex-vereador tem janela conhecida e
  // legislatura null); a tela nunca deriva um do outro. Sem a palavra "Vereador(a)": o contrato não tem
  // gênero e a página é nominal, então o rótulo institucional sem flexão é o único honesto.
  const papel = p.legislatura
    ? `${p.legislatura.numero}ª Legislatura (${p.legislatura.anoInicio}–${p.legislatura.anoFim})`
    : null;

  return {
    nome,
    nomeSecundario,
    iniciais: derivarIniciais(nome),
    papel,
    // valor livre, cru, sem mapa de rótulos — um mapa engoliria o vocabulário do tenant.
    cargoMesa: p.cargoMesa,
    // sem filtrar e SEM CONTADOR: o contrato não expõe o `tipo` de cada comissão, então a entrada da Mesa
    // não é identificável e um "N comissões" contaria a Mesa duas vezes (falso). Carry: expor `tipo`.
    // DEDUPLICADO: `[:vector :string]` não garante unicidade e a origem (`cadastros/db/comissao.clj`,
    // `comissoes-do-vereador`) faz LEFT JOIN com `comissao_cargo` SEM `DISTINCT` — e não há UNIQUE em
    // (ente_id, comissao_id, vereador_id) nem EXCLUDE de vigências sobrepostas (só a Mesa ativa tem um).
    // Dois cargos vigentes na mesma comissão multiplicam a linha e o adapter faz `(mapv :nome ...)` cru:
    // sairiam dois chips idênticos e uma colisão de `key` no React. Deduplicar é decisão de APRESENTAÇÃO,
    // logo mora aqui; `Set` preserva a ordem de primeira aparição. Carry: `DISTINCT` no SQL de origem.
    comissoes: [...new Set(p.comissoes)],
    comissoesRotulo: ROTULO_COMISSOES,
    comissoesVazio: p.comissoes.length === 0 ? VAZIO_COMISSOES : null,
  };
}

function linhaMateria(m: MateriaDeAutoriaOut, ente: string): LinhaMateriaVista {
  const ref = derivarRef(m);
  const { estagios } = derivarTramitacao(m.estado);
  return {
    proposicaoId: m.proposicaoId,
    ref,
    ementa: m.ementa,
    estagios,
    rotuloAria: descreverFaixa(ref, estagios),
    href: `/portal/casa/${encodeURIComponent(ente)}/materias/${encodeURIComponent(m.proposicaoId)}`,
  };
}

export function derivarAutoria(p: PerfilVereadorOut, ente: string): AutoriaVista {
  return {
    linhas: p.materias.map((m) => linhaMateria(m, ente)),
    materiasTotal: p.materiasTotal,
    normasDeAutoria: p.normasDeAutoria,
    votosTotal: p.votosTotal,
    truncamento:
      p.materiasTotal > p.materias.length
        ? truncamentoMaterias(p.materias.length, p.materiasTotal)
        : null,
    vazio: p.materias.length === 0 ? VAZIO_MATERIAS : null,
    // SEMPRE, nunca condicional: é a linha que impede `materiasTotal: 0` / `normasDeAutoria: 0` de virar a
    // afirmação falsa "este vereador não é autor de nada". Por isso são DUAS: uma para a lista e outra
    // para os contadores, cada uma renderizada ADJACENTE ao que qualifica (o JSX não pode escolher).
    recorteAcervo: recorteAcervoAutoria(formatarDataSimples(p.acervoComEloDeAutoriaDesde)),
    recorteNumeros: recorteNumerosAutoria(formatarDataSimples(p.acervoComEloDeAutoriaDesde)),
  };
}

/** Forma EXATA em que o wire monta `materia-rotulo`: `adapters/out/parlamentar.clj` faz
 *  `(str tipo " " sequencial "/" ano)` — o tipo CRU do domínio (`projeto_lei`, minúsculas com `_`) e o
 *  sequencial SEM zero-padding. Os grupos são exatamente o que `derivarRef` precisa. */
const FORMA_ROTULO_DO_WIRE = /^([a-z][a-z_]*) (\d+)\/(\d{4})$/;

/** Reconstitui a referência canônica ("PL 022/2026") a partir do rótulo cru do wire.
 *  POR QUE AQUI: publicado como vem, o voto exibia `projeto_lei 22/2026` enquanto a MESMA proposição
 *  aparecia como `PL 022/2026` na seção "Matérias de autoria" logo acima — duas grafias na mesma página,
 *  e uma delas é vocabulário interno de banco numa superfície pública e nominal. `derivarRef` é a mesma
 *  função que a lista usa, então as duas seções não podem divergir de novo.
 *  FAIL-CLOSED: o que não casar com a forma sai CRU (inclusive o fallback "voto em matéria não publicada").
 *  CARRY (conserto de raiz, exige codegen): expor `materia-tipo`/`materia-sequencial`/`materia-ano` em
 *  `VotoPublicoOut` — os três já são lidos em `db/parlamentar.clj:124` — e montar por `derivarRef` direto,
 *  sem passar por string. Enquanto isso, isto é reconstituição de forma documentada, não adivinhação. */
function normalizarRotuloMateria(rotulo: string): string {
  const m = FORMA_ROTULO_DO_WIRE.exec(rotulo);
  if (!m) return rotulo;
  return derivarRef({ tipo: m[1], sequencial: Number(m[2]), ano: Number(m[3]) });
}

function linhaVoto(v: VotoPublicoOut): LinhaVotoVista {
  // a linha NUNCA é omitida quando a matéria não foi projetada — omitir voto é editar o histórico de
  // uma pessoa. O que falta é o rótulo da matéria, e é isso que a copy diz.
  const rotulo = v.materiaRotulo ? normalizarRotuloMateria(v.materiaRotulo) : VOTO_SEM_MATERIA;
  const quando = formatarData(v.ocorridoEm); // Instant com hora — aqui `formatarData` é o correto.
  return {
    votacaoId: v.votacaoId,
    voto: v.voto,
    rotulo,
    ementa: v.materiaEmenta,
    quando,
    // `materia-rotulo` e `materia-ementa` vêm do MESMO LEFT JOIN e caem nulos JUNTOS. O título da linha é
    // `ementa ?? rotulo`; sem esta resolução aqui, a sublinha repetia o fallback e a linha saía com
    // "voto em matéria não publicada" impresso duas vezes — inclusive para o leitor de tela.
    subtitulo: v.materiaEmenta ? `${rotulo} · ${quando}` : quando,
    votoRotulo: VOTO_ROTULO[v.voto] ?? v.voto,
    votoClasse: VOTO_CLASSE[v.voto] ?? "chip-neutro",
  };
}

export function derivarVotos(p: PerfilVereadorOut): VotosVista {
  // cópia antes do sort (padrão de ficha-materia-vista.ts): `sort` in-place mutaria o array do wire, que é
  // estado de React. ISO-8601 ordena lexicograficamente, então `localeCompare` basta e não constrói Date.
  const ordenados = [...p.votos].sort((a, b) => b.ocorridoEm.localeCompare(a.ocorridoEm));
  return {
    linhas: ordenados.map(linhaVoto),
    truncamento:
      p.votosTotal > p.votos.length ? truncamentoVotos(p.votos.length, p.votosTotal) : null,
    vazio: p.votos.length === 0 ? VAZIO_VOTOS : null,
  };
}

export function derivarPerfil(p: PerfilVereadorOut, ente: string): PerfilVista {
  return {
    identidade: derivarIdentidade(p),
    presenca: derivarPresenca(p.presenca, p.presencaProjetadaDesde),
    autoria: derivarAutoria(p, ente),
    votos: derivarVotos(p),
  };
}
