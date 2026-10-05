// Rótulos de exibição do domínio de sessões. O backend transporta o ENUM (`ordinaria`, `extraordinaria`,
// …) — sem acento e em minúscula, porque é chave, não texto de tela. O telão vinha renderizando a chave
// crua ("Sessão ordinaria nº 1"), o que numa Casa brasileira é erro de português visível ao público no
// painel do plenário.
//
// Os valores vêm do CHECK de `sessoes.sessao.tipo_sessao` (a FONTE), não de memória:
//   CHECK (tipo_sessao IN ('ordinaria','extraordinaria','solene','secreta','especial','audiencia_publica'))
// (`audiencia_publica` entra com a ADR-0021, migration 20261003000182.)
// Mesma mecânica de `NOME_TIPO_ITEM` que a tela do plenário já usa para os itens de pauta — é a
// convenção da casa para enum de domínio virando texto, não um conceito novo.
//
// Fallback: chave desconhecida devolve a própria chave. Uma sessão de tipo novo aparece com o nome
// técnico (feio, mas verdadeiro) em vez de sumir da tela.
const NOME_TIPO_SESSAO: Record<string, string> = {
  ordinaria: "ordinária",
  extraordinaria: "extraordinária",
  solene: "solene",
  secreta: "secreta",
  especial: "especial",
  audiencia_publica: "audiência pública",
};

export function nomeTipoSessao(tipo: string | null | undefined): string {
  if (!tipo) return "";
  return NOME_TIPO_SESSAO[tipo] ?? tipo;
}

// O NOME da sessão numerada, uma fonte só para todas as telas: "15ª Sessão Ordinária". A audiência pública não é
// "sessão" no nome que a Casa usa: "Audiência pública nº 2", o mesmo do cabeçalho da Mesa da audiência. Antes cada
// tela montava o seu e o portal e o livro de atas diziam "2ª Sessão Audiência pública".
export function nomeDaSessao(numeroSequencial: number, tipo: string | null | undefined): string {
  if (tipo === "audiencia_publica") return `Audiência pública nº ${numeroSequencial}`;
  const t = nomeTipoSessao(tipo);
  return `${numeroSequencial}ª Sessão ${t ? t[0].toUpperCase() + t.slice(1) : ""}`.trim();
}

/** O nome da sessão no cabeçalho das telas da Mesa, como o Comando da Mesa o escreve: "Sessão ordinária nº 15". A
 *  audiência pública não é "Sessão audiência pública": é "Audiência pública nº 3". Sem número, nada — o cabeçalho
 *  fica como estava, sem nome inventado. */
export function nomeDaSessaoNoCabecalho(
  sessao: { tipoSessao?: string | null; numeroSequencial?: number | null } | null | undefined,
): string | null {
  const numero = sessao?.numeroSequencial;
  if (!sessao?.tipoSessao || !numero) return null;
  if (sessao.tipoSessao === "audiencia_publica") return `Audiência pública nº ${numero}`;
  return `Sessão ${nomeTipoSessao(sessao.tipoSessao)} nº ${numero}`;
}

// A FASE do rito. Mesma mecânica e mesmo motivo do tipo de sessão acima: o backend transporta a chave
// (`ordem_do_dia`), e a tribuna do plenário renderizava essa chave crua — com o CSS pondo em
// maiúscula, o painel exibia "ORDEM_DO_DIA" ao público. O mapa vivia local em plenario/page.tsx
// (NOME_FASE) e só era aplicado aos itens de pauta; a tribuna não o chamava. Uma fonte só, aqui.
//
// Valores da FONTE — o contrato gerado (lib/contrato-sessoes.gen.ts, campo `fase`):
//   "expediente" | "explicacoes_pessoais" | "grande_expediente" | "ordem_do_dia" | "tribuna_livre_cidadao"
const NOME_FASE: Record<string, string> = {
  expediente: "Expediente",
  grande_expediente: "Grande Expediente",
  ordem_do_dia: "Ordem do Dia",
  explicacoes_pessoais: "Explicações Pessoais",
  tribuna_livre_cidadao: "Tribuna Livre",
};

export function nomeFase(fase: string | null | undefined): string {
  if (!fase) return "";
  return NOME_FASE[fase] ?? fase;
}

// O TIPO DA FALA. Terceiro enum do mesmo domínio que chegava cru ao telão (junto com tipo de sessão e
// fase): a tribuna renderizava `{o.tipoFala}` direto do evento. "principal" passa despercebido porque
// coincide com português correto; "pela_ordem" e "questao_de_ordem" não — sairiam com underscore,
// à vista do público, na tela HERO.
//
// Valores da FONTE — `sessoes/logic.clj`, `(def tipos-fala ...)`:
//   #{"principal" "aparte" "pela_ordem" "questao_de_ordem" "explicacao_pessoal" "comunicado"}
const NOME_TIPO_FALA: Record<string, string> = {
  principal: "Fala principal",
  aparte: "Aparte",
  pela_ordem: "Pela ordem",
  questao_de_ordem: "Questão de ordem",
  explicacao_pessoal: "Explicação pessoal",
  comunicado: "Comunicado",
};

export function nomeTipoFala(tipo: string | null | undefined): string {
  if (!tipo) return "";
  return NOME_TIPO_FALA[tipo] ?? tipo;
}
