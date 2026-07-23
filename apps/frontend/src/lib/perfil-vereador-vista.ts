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
  recorteAcervo: string; // SEMPRE presente
};

export type LinhaVotoVista = {
  votacaoId: string;
  rotulo: string; // materiaRotulo OU "voto em matéria não publicada"
  ementa: string | null;
  quando: string; // dd/mm/aaaa
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

// ---- [COPY NOVA] truncamento — o par lista+total é OBRIGATÓRIO no contrato.
const truncamentoMaterias = (mostradas: number, total: number) =>
  `Mostrando as ${mostradas} matérias mais recentes, de ${total} no total.`;
const truncamentoVotos = (mostrados: number, total: number) =>
  `Mostrando os ${mostrados} votos mais recentes, de ${total} no total.`;

// ---- [COPY NOVA] vazios que EXPLICAM (GUIDELINES §2), nunca afirmam conduta.
const VAZIO_MATERIAS = "Nenhuma matéria de autoria consta desta lista.";
const VAZIO_VOTOS = "Ainda não há votos nominais registrados para este vereador.";
const VAZIO_COMISSOES = "Não há comissões registradas para este vereador.";

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
 *   2º  janelaAnteriorAProjecao — ANTES de sessoesComChamada === 0. Um mandato inteiramente anterior ao
 *       registro eletrônico publica 0/0 COM conhecida=true; lê-lo como "ainda não houve sessão" ou como
 *       "faltou a tudo" é falso sob o nome de uma pessoa (§2, 4º caso — o campo existe só para isso).
 *   3º  sessoesComChamada === 0
 *   4º  a fração
 * Trocar 2º e 3º de lugar reintroduz exatamente o bug que a revisão da fatia 6 fechou. */
export function derivarPresenca(p: PresencaOut, presencaProjetadaDesde: string): PresencaVista {
  const marco = marcoRegistro(formatarDataSimples(presencaProjetadaDesde));

  if (!p.janelaDeExercicioConhecida) {
    return { tipo: "sem-janela", titulo: SEM_JANELA_TITULO, texto: SEM_JANELA_TEXTO, marco };
  }
  if (p.janelaAnteriorAProjecao) {
    return fracao(p, RESSALVA_ANTERIOR_A_PROJECAO, marco);
  }
  if (p.sessoesComChamada === 0) {
    return { tipo: "sem-sessao", texto: SEM_SESSAO, marco, ata: ATA_DOCUMENTO_DE_FE };
  }
  return fracao(p, null, marco);
}

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
    comissoes: p.comissoes,
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
    // afirmação falsa "este vereador não é autor de nada".
    recorteAcervo: recorteAcervoAutoria(formatarDataSimples(p.acervoComEloDeAutoriaDesde)),
  };
}

function linhaVoto(v: VotoPublicoOut): LinhaVotoVista {
  return {
    votacaoId: v.votacaoId,
    // a linha NUNCA é omitida quando a matéria não foi projetada — omitir voto é editar o histórico de
    // uma pessoa. O que falta é o rótulo da matéria, e é isso que a copy diz.
    rotulo: v.materiaRotulo ?? VOTO_SEM_MATERIA,
    ementa: v.materiaEmenta,
    quando: formatarData(v.ocorridoEm), // Instant com hora — aqui `formatarData` é o correto.
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
