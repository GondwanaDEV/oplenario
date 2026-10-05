// Lógica pura da TRILHA DE AUDITORIA da Casa (ADR-0017; porte de produto/design-system/o-plenario/telas/
// trilha-auditoria.html, arquétipo lista/tabela): o livro-razão imutável — quem, o quê, sobre o quê, quando, de onde.
//
// O ESCOPO vem do servidor, pelo papel (Eixo 2-B): o `auditor` vê a Casa inteira, o `admin_ente` os atos de acesso e
// os próprios, qualquer outra pessoa só a própria trilha. A tela não decide nada disso — só mostra o que veio e diz
// qual é o recorte.
//
// DESVIOS DO DESIGN, todos por honestidade:
//   • "Antes → Depois" no detalhe — a trilha NUNCA guarda conteúdo (Eixo 1-C): o antes/depois é da versão do próprio
//     objeto. O detalhe mostra o que o registro tem: a ação, os NOMES dos campos alterados, a decisão, o canal e o selo
//     encadeado ao anterior;
//   • a busca livre ("nome, nº da matéria, IP…") — o servidor filtra por período, tipo de ator, tipo de registro e
//     objeto, e só; sem campo de busca que não busca;
//   • "Operador da plataforma" como ator da lista — a atuação da Operação vive na corrente DELA (ADR-0016) e aparece
//     numa lista à parte, para o auditor, com o selo daquela corrente;
//   • o verbo da ação ("Criou", "Aprovou"…) é derivado do nome da ação do catálogo; o que não se deixa classificar sai
//     como "Registrou", nunca um verbo chutado.

export type AtorTrilha = { tipo: "pessoa" | "cidadao" | "agente"; nome: string | null; papeis: string[]; via: string | null };

export type RegistroTrilha = {
  seq: number;
  em: string;
  ator: AtorTrilha;
  acao: string;
  classe: "escrita" | "negacao" | "entrada" | "leitura_sensivel";
  // "sem_desfecho": a escrita foi iniciada e o desfecho dela não foi registrado — o ato pode ter acontecido (ADR-0017)
  decisao: "permitido" | "negado" | "falhou" | "sem_desfecho";
  recurso: { tipo: string | null; id: string | null; rotulo: string | null } | null;
  campos: string[];
  canal: string;
  ip: string | null;
  selo: string;
  seloAnterior: string;
};

export type AtuacaoOperacao = { em: string; acao: string; operador: string | null; selo: string };

export type Trilha = {
  escopo: "casa" | "acessos" | "propria";
  total: number;
  totalDaCasa: number | null;
  registros: RegistroTrilha[];
  proximo: number | null;
  operacao: AtuacaoOperacao[] | null;
};

export type SeloDoDia = { dia: string; seq: number; selo: string };
export type Integridade = {
  integra: boolean;
  total: number;
  cabeca: string | null;
  quebraEm: number | null;
  selosDoDia: SeloDoDia[];
  /** Escritas iniciadas sem desfecho registrado, e o nº da mais antiga. */
  semDesfecho?: number;
  primeiroSemDesfecho?: number | null;
};

// ---- filtros (o vocabulário é o do servidor: adapters/in/filtro.clj) ----

export type Periodo = "7" | "30" | "ano" | "tudo";
export type Filtro = { periodo: Periodo; ator: string; classe: string; objeto: string };

export const FILTRO_INICIAL: Filtro = { periodo: "30", ator: "", classe: "", objeto: "" };

export const PERIODOS: { valor: Periodo; rotulo: string }[] = [
  { valor: "7", rotulo: "Últimos 7 dias" },
  { valor: "30", rotulo: "Últimos 30 dias" },
  { valor: "ano", rotulo: "Este ano" },
  { valor: "tudo", rotulo: "Desde o início" },
];

export const ATORES: { valor: string; rotulo: string }[] = [
  { valor: "", rotulo: "Todos" },
  { valor: "pessoa", rotulo: "Servidores e vereadores" },
  { valor: "cidadao", rotulo: "Cidadãos (portal)" },
  { valor: "agente", rotulo: "Agentes de IA" },
];

export const CLASSES: { valor: string; rotulo: string }[] = [
  { valor: "", rotulo: "Todos" },
  { valor: "escrita", rotulo: "Atos (escrita)" },
  { valor: "negacao", rotulo: "Acessos negados" },
  { valor: "entrada", rotulo: "Entradas no sistema" },
  { valor: "leitura_sensivel", rotulo: "Consultas à trilha" },
  { valor: "sem_desfecho", rotulo: "Sem desfecho registrado" },
];

export const OBJETOS: { valor: string; rotulo: string }[] = [
  { valor: "", rotulo: "Tudo" },
  { valor: "legislativo", rotulo: "Proposições e tramitação" },
  { valor: "sessoes", rotulo: "Sessões e atas" },
  { valor: "cadastros", rotulo: "Cadastros" },
  { valor: "identidade", rotulo: "Acessos e identidade" },
  { valor: "participacao", rotulo: "Participação (e-SIC, ouvidoria)" },
  { valor: "transparencia", rotulo: "Transparência" },
  { valor: "compliance", rotulo: "Compliance e remessa" },
  { valor: "normas", rotulo: "Normas da Casa" },
  { valor: "integracao-ia", rotulo: "IA da Casa" },
  { valor: "paineis", rotulo: "Painéis" },
  { valor: "auditoria", rotulo: "A própria trilha" },
];

const DIA_ISO = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Fortaleza", year: "numeric", month: "2-digit", day: "2-digit" });

/** O dia civil da Casa (AAAA-MM-DD) de `agora` menos `dias`. */
function diaDaCasa(agora: Date, dias = 0): string {
  return DIA_ISO.format(new Date(agora.getTime() - dias * 86_400_000));
}

/** O filtro da tela -> a query string do servidor (sem `?`). `antesDe` = a página seguinte (keyset por seq). */
export function queryDoFiltro(f: Filtro, agora: Date, antesDe?: number | null): string {
  const p = new URLSearchParams();
  if (f.periodo === "7") p.set("desde", diaDaCasa(agora, 6));
  else if (f.periodo === "30") p.set("desde", diaDaCasa(agora, 29));
  else if (f.periodo === "ano") p.set("desde", `${diaDaCasa(agora).slice(0, 4)}-01-01`);
  if (f.ator) p.set("ator", f.ator);
  if (f.classe) p.set("classe", f.classe);
  if (f.objeto) p.set("objeto", f.objeto);
  if (antesDe) p.set("antes-de", String(antesDe));
  return p.toString();
}

// ---- cada registro, em palavras ----

export type Verbo = { rotulo: string; tom: "criou" | "editou" | "aprovou" | "removeu" | "exportou" | "entrou" | "negado" };

const VERBOS: [RegExp, Verbo][] = [
  [/(retirar|revogar|remover|cancelar|desligar|arquivar|excluir|apagar|descartar|encerrar-vinculo)/, { rotulo: "Removeu", tom: "removeu" }],
  [/(aprovar|assinar|publicar|votar|despachar|conceder|homologar|deferir|sancionar|confirmar|ligar|receber|responder)/, { rotulo: "Aprovou", tom: "aprovou" }],
  [/(editar|atualizar|alterar|reordenar|corrigir|retificar|mudar|trocar|substituir)/, { rotulo: "Editou", tom: "editou" }],
  [/(criar|protocolar|registrar|agendar|incluir|abrir|importar|convidar|enviar|gerar|novo|nova|adicionar|anunciar)/, { rotulo: "Criou", tom: "criou" }],
];

export function verbo(r: RegistroTrilha): Verbo {
  if (r.classe === "negacao") return { rotulo: "Negado", tom: "negado" };
  if (r.classe === "entrada") return { rotulo: "Entrou", tom: "entrou" };
  if (r.acao === "auditoria/exportar" || r.acao === "exportacao-da-casa/baixar") return { rotulo: "Exportou", tom: "exportou" };
  if (r.classe === "leitura_sensivel") return { rotulo: "Consultou", tom: "entrou" };
  if (r.decisao === "sem_desfecho") return { rotulo: "Sem desfecho", tom: "negado" };
  if (r.decisao === "falhou") return { rotulo: "Não concluiu", tom: "negado" };
  const nome = r.acao.split("/")[1] ?? r.acao;
  const achado = VERBOS.find(([re]) => re.test(nome));
  // o título já é a frase do ato ("Definiu os tempos regimentais…"): a etiqueta não repete um segundo verbo, só dá a cor
  if (ehFraseDoAto(r.recurso?.rotulo)) return { rotulo: "Ato", tom: achado ? achado[1].tom : "entrou" };
  return achado ? achado[1] : { rotulo: "Registrou", tom: "entrou" };
}

/** O rótulo é uma frase que começa por verbo no passado ("Revogou um acesso à Casa", "complementou a resposta…"),
 * e não o nome de um objeto ("PL 7/2026"). Vem do resumo por ação (ADR-0017 1-C) ou do handler. */
export function ehFraseDoAto(rotulo: string | null | undefined): boolean {
  return !!rotulo && /^\p{L}+(ou|iu|eu|ôs)(-se)?\s/iu.test(rotulo);
}

const TIPOS_DE_RECURSO: Record<string, string> = {
  proposicao: "Proposição", sessao: "Sessão", ata: "Ata", vereador: "Vereador(a)", identidade: "Pessoa",
  pedido: "Pedido de e-SIC", manifestacao: "Manifestação", norma: "Norma", documento: "Documento",
  proposta: "Proposta do agente", nota: "Nota técnica", remessa: "Remessa", item: "Item da pauta",
  exportacao: "Exportação completa",
};

const NOMES_DE_MODULO: Record<string, string> = Object.fromEntries(OBJETOS.filter((o) => o.valor).map((o) => [o.valor, o.rotulo]));

/** "legislativo/despachar-proposicao" -> "despachar proposicao · Proposições e tramitação". */
export function acaoEmPalavras(acao: string): string {
  const [modulo, nome] = acao.includes("/") ? acao.split("/", 2) : ["", acao];
  const oque = nome.replace(/-/g, " ");
  return NOMES_DE_MODULO[modulo] ? `${oque} · ${NOMES_DE_MODULO[modulo]}` : oque;
}

export function objeto(r: RegistroTrilha): { titulo: string; detalhe: string } {
  const rec = r.recurso;
  // tipo que a tela não conhece (o parâmetro cru da rota, "id") não vira título: "id 17d4218b" não diz nada a ninguém
  const tipo = rec?.tipo ? TIPOS_DE_RECURSO[rec.tipo] ?? null : null;
  const titulo = rec?.rotulo ?? (tipo ? `${tipo}${rec?.id ? ` ${rec.id.slice(0, 8)}` : ""}` : acaoEmPalavras(r.acao));
  const modulo = NOMES_DE_MODULO[r.acao.split("/")[0]] ?? null;
  // a frase do ato já diz o que foi feito: o detalhe só situa (o módulo), sem repetir a ação em infinitivo
  const detalhe = [ehFraseDoAto(rec?.rotulo) ? modulo : rec?.rotulo || tipo ? acaoEmPalavras(r.acao) : null,
    r.decisao === "negado" ? "barrado pela política de acesso" : null,
    r.decisao === "falhou" ? "o sistema recusou o pedido" : null,
    r.decisao === "sem_desfecho" ? "ação iniciada, desfecho não registrado" : null].filter(Boolean).join(" · ");
  return { titulo, detalhe };
}

/** A decisão do registro em palavras. O que o servidor mandar de novo aparece como está, nunca como "Não concluído". */
export function decisaoEmPalavras(d: RegistroTrilha["decisao"] | string): string {
  switch (d) {
    case "permitido": return "Permitido";
    case "negado": return "Negado pela política";
    case "falhou": return "Não concluído";
    case "sem_desfecho": return "Ação iniciada, desfecho não registrado — confira se o ato aconteceu";
    default: return `Não reconhecida (${d})`;
  }
}

const PAPEIS: Record<string, string> = {
  secretario: "Secretaria", vereador: "Vereador(a)", admin_ente: "Administração", auditor: "Controle interno",
};

export function quem(a: AtorTrilha): { nome: string; papel: string; iniciais: string } {
  const nome =
    a.tipo === "agente" && !a.nome ? "Agente institucional" : a.nome ?? (a.tipo === "cidadao" ? "Cidadão" : "Pessoa sem nome no cadastro");
  const papel =
    a.tipo === "cidadao" ? "Portal do cidadão"
    : a.via ? `via agente ${a.via}`
    : a.papeis.map((p) => PAPEIS[p] ?? p).join(" · ") || (a.tipo === "agente" ? "Agente da Casa" : "Sem papel");
  const iniciais = a.tipo === "cidadao" ? "#" : nome.split(/\s+/).filter(Boolean).map((p) => p[0]).slice(0, 2).join("").toUpperCase();
  return { nome, papel, iniciais };
}

const DATA = new Intl.DateTimeFormat("pt-BR", { timeZone: "America/Fortaleza", day: "2-digit", month: "2-digit", year: "numeric" });
const HORA = new Intl.DateTimeFormat("pt-BR", { timeZone: "America/Fortaleza", hour: "2-digit", minute: "2-digit", second: "2-digit" });

export function quando(iso: string): { data: string; hora: string } {
  const d = new Date(iso);
  return { data: DATA.format(d), hora: HORA.format(d) };
}

/** O selo curto do design: "a7f3·9c21" (os 8 primeiros hex). */
export function seloCurto(selo: string | null | undefined): string {
  if (!selo) return "—";
  return `${selo.slice(0, 4)}·${selo.slice(4, 8)}`;
}

const CANAIS: Record<string, string> = {
  web: "navegador", "app-vereador": "app do vereador", "painel-mesa": "painel da Mesa", captacao: "captação", agente: "agente",
};
export function canal(c: string): string {
  return CANAIS[c] ?? c;
}

const inteiro = new Intl.NumberFormat("pt-BR");
export function numero(n: number): string {
  return inteiro.format(n);
}

export function rotuloDoEscopo(e: Trilha["escopo"]): string {
  if (e === "casa") return "Toda a Casa";
  if (e === "acessos") return "Os atos de acesso e os seus";
  return "Os seus próprios atos";
}

export function explicacaoDoEscopo(e: Trilha["escopo"]): string | null {
  if (e === "acessos")
    return "Como administrador da Casa, você vê os atos de acesso (quem concedeu, revogou e entrou) e os seus. A trilha inteira é do controle interno.";
  if (e === "propria")
    return "Você vê a sua própria trilha: cada ato que fez no sistema. A trilha da Casa inteira é do controle interno.";
  return null;
}

/** O estado do lacre de integridade — nunca "íntegra" sem ter conferido. */
export function lacre(i: Integridade | null, estado: "carregando" | "pronto" | "erro"): { titulo: string; texto: string; quebrada: boolean } {
  if (estado === "carregando") return { titulo: "Conferindo a cadeia…", texto: "Cada selo é recalculado a partir do anterior.", quebrada: false };
  if (estado === "erro" || !i)
    return { titulo: "Não foi possível conferir agora", texto: "A cadeia não foi verificada nesta visita. Tente de novo em instantes.", quebrada: false };
  if (i.integra && (i.semDesfecho ?? 0) > 0) {
    const n = i.semDesfecho ?? 0;
    return {
      titulo: "Cadeia íntegra, com desfecho faltando",
      texto: `${n === 1 ? "Uma ação foi iniciada" : `${numero(n)} ações foram iniciadas`} e o desfecho não foi registrado`
        + `${i.primeiroSemDesfecho ? ` (a mais antiga é o registro nº ${numero(i.primeiroSemDesfecho)})` : ""}.`
        + " Filtre por “Sem desfecho registrado” e confira se o ato aconteceu.",
      quebrada: false,
    };
  }
  if (i.integra)
    return {
      titulo: "Cadeia íntegra",
      texto: "Cada evento traz um selo encadeado ao anterior — qualquer alteração quebraria a sequência.",
      quebrada: false,
    };
  return {
    titulo: "Cadeia quebrada",
    texto: `O registro nº ${numero(i.quebraEm ?? 0)} não confere com o selo anterior. Guarde a exportação e acione a Operação.`,
    quebrada: true,
  };
}
