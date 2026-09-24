// O letreiro do rodapé do Modo TV (docs/22) — as frases "de telejornal" sobre o que está acontecendo. PURO e
// testado (tv-letreiro.test.ts).
//
// As frases saem do ESTADO ATUAL, nunca de um histórico de eventos. O canal replaya a sessão desde `id: 1` a
// cada conexão: um letreiro narrado a partir dos eventos anunciaria, numa TV recarregada, "votação aberta"
// de uma matéria encerrada há uma hora. Derivado do estado, ele diz só o que é verdade agora — e a última
// votação aparece como "Última votação", no passado.

import type { PautaOut, SessaoOut } from "./contrato";
import { formatarHora } from "./formatar-data";
import type { EstadoPlenario } from "./plenario-reducer";
import { materiaDoPlacar, vistaApreciacaoTv, vistaTribunaTv, vistaVotacaoTv } from "./tv-vista";

/** Uma frase com UM trecho em destaque (renderizado em negrito/âmbar): `antes` + `destaque` + `depois`. */
export interface FraseLetreiro {
  antes: string;
  destaque: string;
  depois: string;
}

const f = (antes: string, destaque: string, depois = ""): FraseLetreiro => ({ antes, destaque, depois });

export function frasesDoLetreiro(
  sessao: Pick<SessaoOut, "aberta-em">,
  estado: EstadoPlenario,
  pauta: PautaOut | null,
  agoraMs: number,
): FraseLetreiro[] {
  const frases: FraseLetreiro[] = [];

  if (estado.estado === "suspensa") {
    frases.push(f("Sessão ", "suspensa", " — os trabalhos serão retomados em instantes"));
  }

  const votacao = vistaVotacaoTv(estado);
  if (votacao) {
    const resto =
      votacao.faltam !== null
        ? ` — ${votacao.faltam === 0 ? "todos os presentes votaram" : `faltam ${votacao.faltam} ${votacao.faltam === 1 ? "voto" : "votos"}`}`
        : ` — ${votacao.votaram} ${votacao.votaram === 1 ? "voto registrado" : "votos registrados"}`;
    frases.push(f("Em votação: ", votacao.numero, resto));
  } else {
    // docs/23 Fatia 4b: a matéria anunciada pela Mesa — só enquanto não há votação aberta (aí ela é o assunto).
    const apreciacao = vistaApreciacaoTv(estado, pauta);
    if (apreciacao) {
      frases.push(f("Em apreciação: ", apreciacao.sigla, apreciacao.autor ? ` — autoria de ${apreciacao.autor}` : ""));
    }
  }

  const tribuna = vistaTribunaTv(estado, agoraMs);
  if (tribuna) frases.push(f("Na tribuna: ", tribuna.nome, tribuna.fase ? ` — ${tribuna.fase}` : ""));

  if (estado.quorum) {
    frases.push(f("Quórum: ", `${estado.quorum.presentesTotal} de ${estado.quorum.membrosDaCasa}`, " vereadores presentes"));
  }

  const placar = estado.placar;
  if (placar?.encerrada && (placar.resultado === "aprovada" || placar.resultado === "rejeitada")) {
    const t = placar.totais;
    const placarFinal =
      t && t.sim !== null && t.nao !== null ? ` por ${t.sim} × ${t.nao}${t.abstencao ? ` (${t.abstencao} abst.)` : ""}` : "";
    frases.push(f("Última votação: ", materiaDoPlacar(placar).numero, ` ${placar.resultado}${placarFinal}`));
  }

  if (pauta && pauta.itens.length > 0) {
    const n = pauta.itens.length;
    frases.push(f("Pauta do dia: ", `${n} ${n === 1 ? "item" : "itens"}`));
  }

  if (sessao["aberta-em"] && estado.estado !== "agendada") {
    frases.push(f("Sessão aberta às ", formatarHora(sessao["aberta-em"])));
  }

  return frases;
}
