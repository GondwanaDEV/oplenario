// View-model puro da tela "Minha atuação" do app do vereador (Onda E, `vereador-estatisticas`) — porte
// HONESTO de produto/design-system/o-plenario/telas/vereador-estatisticas.html. Zero React, zero fetch.
//
// FONTE: autoria, "viraram lei" e presença são os MESMOS do perfil público do vereador
// (GET /portal/casa/{ente}/vereadores/{id}, `PerfilVereadorOut`), mais a contagem de pareceres em que ele é
// relator, que vem do painel dele (GET /meu/painel). Uma fonte só para o que o cidadão também vê: o
// vereador nunca enxerga aqui um número diferente do que está publicado sob o nome dele.
//
// OS VOTOS SÃO A EXCEÇÃO, e é de propósito: vêm de GET /meu/votos (`MeusVotosOut`), rota autenticada que só
// devolve os votos nominais do próprio ator. O perfil público só conta voto de sessão pública; o vereador
// também vê aqui o que votou em sessão secreta ou fechada ao público, marcado como "só você vê". Voto de
// votação secreta não existe por vereador (o sigilo é do schema) e nunca aparece.
//
// O QUE O DESIGN TEM E ESTA TELA NÃO (porque o dado não existe, e inventar seria pior que omitir):
//   • os 5 grupos fixos de situação ("Em comissão", "Aprovadas, aguardam sanção"…) — o estado da
//     proposição é o vocabulário do rito DE CADA CASA (template por Câmara, Invariante 4). Agrupamos pelo
//     estado que a Casa usa, com o rótulo de `derivarTramitacao`, em vez de forçar 5 baldes que não casam;
//   • "96% presença" — percentual é proibido (docs/14 §9): sai a fração "X de Y", com a mesma resolução de
//     estado do perfil público (`derivarPresenca`);
//   • presença por mês e "ausência justificada" — o read-model só tem o total, e justificativa de ausência
//     não é registrada;
//   • o seletor de período (Legislatura/2026/Este mês) — o perfil não é recortável por período.

import type { PerfilVereadorOut } from "./contrato-portal.gen";
import type { MeuPainelOut, MeusVotosOut, MeuVotoOut } from "./contrato-legislativo.gen";
import { derivarPresenca, VOTO_CLASSE, VOTO_ROTULO, type PresencaVista } from "./perfil-vereador-vista";
import { derivarRef } from "./materia-vista";
import { derivarTramitacao } from "./tramitacao-vista";
import { formatarData, formatarDataSimples } from "./formatar-data";

export type CartaoAtuacao = { valor: string; rotulo: string };

export type SituacaoAtuacao = {
  estado: string; // chave crua do rito da Casa (React key)
  rotulo: string;
  quantidade: number;
  largura: number; // 0–100, proporção dentro das matérias listadas
};

export type LinhaVotoAtuacao = {
  votacaoId: string; // React key
  titulo: string; // a ementa da matéria, ou o rótulo de quando não há proposição identificada
  subtitulo: string; // "PL 022/2026 · 18/05/2026" — só a data quando o título já é o rótulo
  voto: string; // valor CRU do wire (sim|nao|abstencao) — chaveia a FORMA do chip, nunca o texto
  votoRotulo: string; // "A favor" | "Contra" | "Absteve-se" | valor cru (fail-closed)
  votoClasse: string; // "chip-ok" | "chip-risco" | "chip-neutro"
  aviso: string | null; // o que o portal faz com este voto, quando não é "publica"; null quando é público
};

export type AtuacaoVista = {
  cartoes: CartaoAtuacao[];
  situacoes: SituacaoAtuacao[];
  situacoesVazio: string | null;
  situacoesRecorte: string | null; // só quando a lista truncou
  presenca: PresencaVista;
  votos: { sim: number; nao: number; abstencao: number };
  votosVazio: string | null;
  linhasVotos: LinhaVotoAtuacao[];
  votosTruncamento: string | null; // só quando a lista truncou
  notas: string[];
};

// ---- copy
const VAZIO_SITUACOES = "Nenhuma proposição de sua autoria consta no acervo publicado.";
const VAZIO_VOTOS = "Ainda não há votos nominais registrados para você.";
const recorteSituacoes = (listadas: number, total: number) =>
  `A situação cobre as ${listadas} proposições de numeração mais alta, de ${total} no total.`;
const notaAcervo = (dataFmt: string) =>
  `Proposições e leis contam a partir de ${dataFmt}, quando o acervo passou a registrar a autoria; ` +
  `matérias protocoladas antes disso não entram nestes números.`;
const NOTA_FONTE =
  "Proposições, leis e presença são os mesmos números do seu perfil público no portal da Casa. Os votos " +
  "vêm do registro das votações nominais e incluem os de sessão fechada ao público, que o portal não mostra. " +
  "Votações secretas não entram: o sistema não guarda quem votou.";
const VOTO_SEM_MATERIA = "Votação sem proposição identificada";
// O que o portal faz com o voto (campo `portal` do wire). "publico" não ganha aviso.
const AVISO_SESSAO_FECHADA = "Sessão fechada ao público — só você vê este voto.";
const AVISO_SEM_SESSAO = "Votação fora de sessão — não aparece no portal, só você vê este voto.";
const AVISO_ANULADA = "Votação anulada — este voto não entra nos números acima.";
const truncamentoVotos = (mostrados: number, total: number) =>
  `Mostrando os ${mostrados} votos mais recentes, de ${total} no total.`;

/** Valor do cartão de presença: a fração quando há denominador, travessão quando não há número a exibir
 *  (o texto que explica o porquê vai na seção de presença, não no cartão). Nunca percentual. */
function valorPresenca(p: PresencaVista): string {
  return p.tipo === "fracao" ? `${p.presente} de ${p.total}` : "—";
}

/** Pareceres como relator: o painel traz no máximo 50 e diz se truncou — com truncamento o número honesto
 *  é "mais de 50", nunca 50. */
function valorPareceres(painel: MeuPainelOut): string {
  const n = painel.pareceres.length;
  return painel.pareceresTruncado ? `mais de ${n}` : String(n);
}

/** Agrupa as matérias pelo estado do rito da Casa. Ordem: mais numeroso primeiro; empate pelo rótulo. */
export function agruparPorSituacao(materias: PerfilVereadorOut["materias"]): SituacaoAtuacao[] {
  const contagem = new Map<string, number>();
  for (const m of materias) contagem.set(m.estado, (contagem.get(m.estado) ?? 0) + 1);
  const total = materias.length;
  return [...contagem.entries()]
    .map(([estado, quantidade]) => ({
      estado,
      rotulo: derivarTramitacao(estado).rotuloSituacao,
      quantidade,
      largura: total > 0 ? Math.round((quantidade / total) * 100) : 0,
    }))
    .sort((a, b) => b.quantidade - a.quantidade || a.rotulo.localeCompare(b.rotulo, "pt-BR"));
}

/** O aviso do voto. Anulada vence: a votação foi desfeita, e dizer onde ela apareceria seria ruído. */
function avisoDoVoto(v: MeuVotoOut): string | null {
  if (v.anulada) return AVISO_ANULADA;
  if (v.portal === "sessao-fechada") return AVISO_SESSAO_FECHADA;
  if (v.portal === "sem-sessao") return AVISO_SEM_SESSAO;
  return null; // "publico": o cidadão também vê este voto no portal
}

export function linhaDeVoto(v: MeuVotoOut): LinhaVotoAtuacao {
  // a linha NUNCA é omitida quando a matéria é desconhecida: omitir voto é editar o histórico de uma pessoa.
  const { materiaTipo: tipo, materiaAno: ano, materiaSequencial: sequencial } = v;
  const ref = tipo !== null && ano !== null && sequencial !== null ? derivarRef({ tipo, ano, sequencial }) : null;
  const quando = formatarData(v.registradoEm);
  return {
    votacaoId: v.votacaoId,
    titulo: v.materiaEmenta ?? ref ?? VOTO_SEM_MATERIA,
    // título e referência caem juntos quando não há matéria: a sublinha vira só a data (não repete o fallback)
    subtitulo: ref !== null ? `${ref} · ${quando}` : quando,
    voto: v.voto,
    votoRotulo: VOTO_ROTULO[v.voto] ?? v.voto,
    votoClasse: VOTO_CLASSE[v.voto] ?? "chip-neutro",
    aviso: avisoDoVoto(v),
  };
}

export function derivarAtuacao(
  perfil: PerfilVereadorOut,
  painel: MeuPainelOut,
  meusVotos: MeusVotosOut,
): AtuacaoVista {
  const presenca = derivarPresenca(perfil.presenca, perfil.presencaProjetadaDesde);
  const situacoes = agruparPorSituacao(perfil.materias);
  const votos = meusVotos.votosPorOpcao;
  return {
    cartoes: [
      { valor: String(perfil.materiasTotal), rotulo: "proposições de autoria" },
      { valor: valorPresenca(presenca), rotulo: "presença em sessões" },
      { valor: String(perfil.normasDeAutoria), rotulo: "viraram lei" },
      { valor: valorPareceres(painel), rotulo: "pareceres como relator" },
    ],
    situacoes,
    situacoesVazio: situacoes.length === 0 ? VAZIO_SITUACOES : null,
    situacoesRecorte:
      perfil.materiasTotal > perfil.materias.length
        ? recorteSituacoes(perfil.materias.length, perfil.materiasTotal)
        : null,
    presenca,
    votos: { sim: votos.sim, nao: votos.nao, abstencao: votos.abstencao },
    votosVazio: meusVotos.votosTotal === 0 ? VAZIO_VOTOS : null,
    linhasVotos: meusVotos.votos.map(linhaDeVoto),
    votosTruncamento:
      meusVotos.votosTotal > meusVotos.votos.length
        ? truncamentoVotos(meusVotos.votos.length, meusVotos.votosTotal)
        : null,
    notas: [notaAcervo(formatarDataSimples(perfil.acervoComEloDeAutoriaDesde)), NOTA_FONTE],
  };
}
