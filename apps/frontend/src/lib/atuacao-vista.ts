// View-model puro da tela "Minha atuação" do app do vereador (Onda E, `vereador-estatisticas`) — porte
// HONESTO de produto/design-system/o-plenario/telas/vereador-estatisticas.html. Zero React, zero fetch.
//
// FONTE: os números são os MESMOS do perfil público do vereador (GET /portal/casa/{ente}/vereadores/{id},
// `PerfilVereadorOut`) — autoria, "viraram lei", presença e votos nominais — mais a contagem de pareceres
// em que ele é relator, que vem do painel dele (GET /meu/painel). Uma fonte só para o que o cidadão também
// vê: o vereador nunca enxerga aqui um número diferente do que está publicado sob o nome dele.
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
import type { MeuPainelOut } from "./contrato-legislativo.gen";
import { derivarPresenca, type PresencaVista } from "./perfil-vereador-vista";
import { derivarTramitacao } from "./tramitacao-vista";
import { formatarDataSimples } from "./formatar-data";

export type CartaoAtuacao = { valor: string; rotulo: string };

export type SituacaoAtuacao = {
  estado: string; // chave crua do rito da Casa (React key)
  rotulo: string;
  quantidade: number;
  largura: number; // 0–100, proporção dentro das matérias listadas
};

export type AtuacaoVista = {
  cartoes: CartaoAtuacao[];
  situacoes: SituacaoAtuacao[];
  situacoesVazio: string | null;
  situacoesRecorte: string | null; // só quando a lista truncou
  presenca: PresencaVista;
  votos: { sim: number; nao: number; abstencao: number };
  votosVazio: string | null;
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
  "São os mesmos números do seu perfil público no portal da Casa, a partir dos registros oficiais de " +
  "presença e das votações nominais. Votações secretas não entram na contagem individual.";

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

export function derivarAtuacao(perfil: PerfilVereadorOut, painel: MeuPainelOut): AtuacaoVista {
  const presenca = derivarPresenca(perfil.presenca, perfil.presencaProjetadaDesde);
  const situacoes = agruparPorSituacao(perfil.materias);
  const votos = perfil.votosPorOpcao;
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
    votosVazio: perfil.votosTotal === 0 ? VAZIO_VOTOS : null,
    notas: [notaAcervo(formatarDataSimples(perfil.acervoComEloDeAutoriaDesde)), NOTA_FONTE],
  };
}
