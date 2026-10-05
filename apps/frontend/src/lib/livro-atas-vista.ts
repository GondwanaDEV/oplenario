// View-model puro do LIVRO DE ATAS (Onda E, `livro-atas`) — o MESMO para a tela interna (/atas) e o portal do
// cidadão (/portal/casa/[ente]/atas), porque o contrato é o mesmo (LivroAtasOut / AtaDoLivroOut). Porte de
// produto/design-system/o-plenario/telas/livro-atas.html com o que o dado sustenta.
//
// DESVIOS DO DESIGN, todos por honestidade:
//   • o design diz "uma ata entra no livro apenas depois de APROVADA em sessão" e carimba "Aprovada". O sistema não
//     registra aprovação da ata pelo plenário — registra a PUBLICAÇÃO (a secretaria revisa e publica) e a LEITURA na
//     sessão seguinte (A.7: lida, em voz sintetizada, ou dispensada). O selo diz "Publicada", e a leitura aparece
//     como o fato que é; nunca "aprovada";
//   • a lombada do design traz "quórum 21/21 · 4 deliberações" — o livro não tem esses números (a ata é texto);
//     sai a versão e a leitura;
//   • as assinaturas da Mesa ao pé da folha — a ata publicada não guarda assinatura (ICP-Brasil é `[GAP]`); sai o
//     selo de integridade (o SHA-256 do texto publicado), que é o que existe;
//   • os filtros de ano e tipo — sem eles nesta fatia: o livro de uma Casa cabe numa página por anos.

import type {
  AtaDoLivroItemOut,
  AtaDoLivroOut,
  LeituraDoLivroOut,
  LivroAtasOut,
  SessaoDoLivroOut,
  VersaoDoLivroOut,
} from "./contrato-sessoes.gen";
import { formatarData } from "./formatar-data";
import { nomeDaSessao } from "./rotulos-sessao";

export type LinhaLivroVista = {
  sessaoId: string;
  numero: string; // "13ª" — a lombada
  titulo: string; // "13ª Sessão Ordinária"
  quando: string; // dd/mm/aaaa da sessão
  detalhe: string; // "versão 2 (retificada) · lida no plenário"
  retificada: boolean;
};

export type LivroVista = {
  linhas: LinhaLivroVista[];
  vazio: string | null;
};

export type VersaoVista = {
  versao: number;
  rotulo: string; // "Versão 2 · 18/09/2026"
  motivo: string | null;
  exibida: boolean;
};

export type AtaVista = {
  titulo: string; // "Ata da 13ª Sessão Ordinária"
  quando: string;
  selo: string; // "Publicada · 17/09/2026"
  paragrafos: string[];
  origem: string;
  publicadaPor: string | null;
  leitura: string | null;
  aviso: string | null; // versão antiga aberta
  integridade: string; // "SHA-256 ab12…"
  versoes: VersaoVista[];
  temRetificacao: boolean;
};

const VAZIO = "Nenhuma ata publicada ainda. A ata entra no livro quando a secretaria a publica, depois da sessão.";

const ORIGEM: Record<string, string> = {
  redigida_externamente: "Redigida pela secretaria da Casa.",
  gerada_automaticamente: "Rascunho preparado pela IA a partir da gravação, revisado e publicado por uma pessoa.",
};

const LEITURA: Record<string, (data: string) => string> = {
  presencial: (d) => `Lida em plenário pela secretaria em ${d}.`,
  voz_sintetizada: (d) => `Lida em plenário, em voz sintetizada, em ${d}.`,
  dispensada: (d) => `Leitura dispensada em plenário em ${d} (ata distribuída antes da sessão).`,
};

function dataDaSessao(s: SessaoDoLivroOut): string {
  const iso = s.abertaEm ?? s.agendadaPara;
  return iso ? formatarData(iso) : "data não registrada";
}

export function tituloSessao(s: SessaoDoLivroOut): string {
  return nomeDaSessao(s.numeroSequencial, s.tipoSessao);
}

function textoLeitura(l: LeituraDoLivroOut | null | undefined, versaoVigente: number): string | null {
  if (!l) return null;
  const base = (LEITURA[l.modo] ?? ((d: string) => `Apresentada em plenário em ${d}.`))(formatarData(l.registradaEm));
  // a leitura é da versão que era vigente na hora; retificação posterior não reescreve o que o plenário ouviu
  return l.ataVersao === versaoVigente ? base : `${base} Foi lida a versão ${l.ataVersao}.`;
}

function linha(a: AtaDoLivroItemOut): LinhaLivroVista {
  const partes = [a.versao > 1 ? `versão ${a.versao} (retificada)` : "versão 1"];
  partes.push(a.leitura ? "apresentada em plenário" : "ainda não apresentada em plenário");
  return {
    sessaoId: a.sessao.id,
    numero: `${a.sessao.numeroSequencial}ª`,
    titulo: tituloSessao(a.sessao),
    quando: dataDaSessao(a.sessao),
    detalhe: partes.join(" · "),
    retificada: a.versao > 1,
  };
}

export function derivarLivro(l: LivroAtasOut): LivroVista {
  return { linhas: l.atas.map(linha), vazio: l.atas.length === 0 ? VAZIO : null };
}

/** O texto publicado em parágrafos (linhas em branco separam). Nada é reescrito: é o texto congelado. */
export function paragrafos(texto: string): string[] {
  return texto
    .split(/\n\s*\n/)
    .map((p) => p.trim())
    .filter((p) => p.length > 0);
}

function versaoVista(v: VersaoDoLivroOut, exibida: number): VersaoVista {
  return {
    versao: v.versao,
    rotulo: `Versão ${v.versao} · ${formatarData(v.publicadaEm)}`,
    motivo: v.motivoRetificacao ?? null,
    exibida: v.versao === exibida,
  };
}

export function derivarAta(a: AtaDoLivroOut): AtaVista {
  const vigente = a.versoes[0]?.versao ?? a.versao.versao;
  return {
    titulo: `Ata da ${tituloSessao(a.sessao)}`,
    quando: dataDaSessao(a.sessao),
    selo: `Publicada · ${formatarData(a.versao.publicadaEm)}`,
    paragrafos: paragrafos(a.texto),
    origem: ORIGEM[a.versao.origemRedacao] ?? "Publicada pela secretaria da Casa.",
    publicadaPor: a.versao.publicadaPorNome ? `Publicada por ${a.versao.publicadaPorNome}.` : null,
    leitura: textoLeitura(a.leitura, vigente),
    aviso: a.vigente
      ? null
      : `Você está lendo a versão ${a.versao.versao}. Ela foi substituída pela versão ${vigente}, a que vale hoje.`,
    integridade: `SHA-256 do texto publicado: ${a.versao.conteudoSha256.replace(/^sha256:/, "")}`,
    versoes: a.versoes.map((v) => versaoVista(v, a.versao.versao)),
    temRetificacao: a.versoes.length > 1,
  };
}
