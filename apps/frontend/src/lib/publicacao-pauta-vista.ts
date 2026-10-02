// View-model PURO de "publicar a pauta" (ADR-0019 fatia 3, Eixo 7). Sem React, sem fetch: testado em
// publicacao-pauta-vista.test.ts. Vocabulário honesto: a publicação é da pauta, os avisos são AVISOS (nunca
// bloqueiam) e ninguém "aprova" a pauta aqui — o sistema registra quem publicou e a que título.

import type {
  AvisoPautaOut,
  PublicacaoPautaOut,
  RegraPautaOut,
  VersaoPautaOut,
} from "./contrato-sessoes.gen";
import { formatarData, formatarHora } from "./formatar-data";
import { formatarNumeroProposicao } from "./proposicoes-vista";

export type QuemPublica = RegraPautaOut["quemPublica"];

export const OPCOES_QUEM_PUBLICA: { valor: QuemPublica; rotulo: string; ajuda: string }[] = [
  { valor: "secretaria", rotulo: "A secretaria legislativa", ajuda: "Padrão: quem monta a pauta também a publica." },
  { valor: "presidente", rotulo: "O Presidente da Câmara", ajuda: "Só quem ocupa a presidência da Mesa vigente." },
  { valor: "primeiro_secretario", rotulo: "O 1º Secretário", ajuda: "Só quem ocupa a 1ª secretaria da Mesa vigente." },
  { valor: "mesa", rotulo: "A Mesa Diretora", ajuda: "Qualquer vereador com cargo na Mesa vigente." },
];

export function rotuloQuemPublica(q: QuemPublica): string {
  return OPCOES_QUEM_PUBLICA.find((o) => o.valor === q)?.rotulo ?? q;
}

/** "dd/mm/aaaa às hh:mm" de um instante ISO. */
export function quando(iso: string): string {
  return `${formatarData(iso)} às ${formatarHora(iso)}`;
}

/** O selo da última publicação: "Publicada v3 em 06/10/2026 às 14:30". */
export function seloDaPublicacao(v: Pick<VersaoPautaOut, "versao" | "publicadaEm"> | null | undefined): string {
  return v ? `Publicada v${v.versao} em ${quando(v.publicadaEm)}` : "Pauta ainda não publicada";
}

/** A matéria de um aviso pelo número ("PL 12/2026"), ou um rótulo honesto quando o resumo não veio. */
function materiaDoAviso(a: AvisoPautaOut): string {
  return a.proposicao ? formatarNumeroProposicao(a.proposicao.tipo, a.proposicao.sequencial, a.proposicao.ano) : "Matéria da pauta";
}

/** A frase de um aviso, para a lista da tela. */
export function textoDoAviso(a: AvisoPautaOut): string {
  switch (a.tipo) {
    case "sem-parecer-comissao":
      return `${materiaDoAviso(a)}: sem parecer da comissão${
        a.pareceresEmAndamento ? ` (${a.pareceresEmAndamento} em andamento)` : ""
      }.`;
    case "pedido-juridico-pendente":
      return `${materiaDoAviso(a)}: pedido de parecer jurídico ainda pendente.`;
    case "antecedencia-nao-cumprida":
      return `Fora da antecedência mínima da Casa: ${a.minimoHoras} h exigidas, ${
        (a.horasReais ?? 0) < 0 ? "a sessão já começou" : `faltam ${a.horasReais} h para o início`
      }.`;
    default:
      return "Aviso da pauta.";
  }
}

export type EstadoBotao = { habilitado: boolean; motivo: string | null };

/** Se o botão "Publicar a pauta" está habilitado e, se não, por quê — na ordem em que a pessoa resolve. */
export function estadoDoBotao(p: PublicacaoPautaOut): EstadoBotao {
  if (!p.podePublicar) return { habilitado: false, motivo: p.motivo ?? "Você não pode publicar esta pauta." };
  if (p.itensNaPauta === 0) return { habilitado: false, motivo: "A pauta não tem item: inclua as matérias antes de publicar." };
  if (p.ultima && !p.alteradaDesdeAPublicacao)
    return { habilitado: false, motivo: `A pauta não mudou desde a publicação v${p.ultima.versao}.` };
  return { habilitado: true, motivo: null };
}

/** "alterada desde a publicação v3" — só quando há publicação e a pauta viva mudou. */
export function avisoDeAlteracao(p: Pick<PublicacaoPautaOut, "ultima" | "alteradaDesdeAPublicacao">): string | null {
  return p.ultima && p.alteradaDesdeAPublicacao ? `Alterada desde a publicação v${p.ultima.versao}` : null;
}

/** A regra da Casa em uma linha, para quem está publicando. */
export function descreverRegra(r: RegraPautaOut): string {
  const quem = rotuloQuemPublica(r.quemPublica).replace(/^A |^O /, (m) => m.toLowerCase());
  const ant = r.antecedenciaMinimaHoras
    ? `antecedência mínima de ${r.antecedenciaMinimaHoras} h antes da sessão`
    : "sem antecedência mínima definida";
  return `Nesta Casa, publica ${quem}; ${ant}.`;
}

/** A mensagem de uma recusa do servidor na publicação (409 com `motivo`). */
export function mensagemDeRecusa(status: number, motivo?: string, erro?: string): string {
  if (status === 403) return "Pela regra desta Casa, você não pode publicar esta pauta.";
  if (status === 404) return "Sessão não encontrada.";
  if (status === 409) {
    switch (motivo) {
      case "pauta-vazia":
        return "A pauta não tem item: inclua as matérias antes de publicar.";
      case "sem-alteracao":
        return "A pauta não mudou desde a última publicação.";
      case "pauta-mudou":
        return "A pauta mudou enquanto você publicava. Confira de novo e publique.";
      case "justificativa-obrigatoria":
        return "Republicar exige dizer o que mudou.";
      default:
        return erro ?? "A pauta não pode ser publicada agora.";
    }
  }
  if (status === 0) return "Sem conexão com o servidor. Tente de novo.";
  return "Não foi possível publicar a pauta. Tente de novo.";
}

/** Horas digitadas no campo de antecedência: vazio = sem regra (null); fora de 1..720 ou não-inteiro = inválido. */
export function lerAntecedencia(texto: string): { ok: true; valor: number | null } | { ok: false } {
  const t = texto.trim();
  if (t === "") return { ok: true, valor: null };
  if (!/^\d+$/.test(t)) return { ok: false };
  const n = Number(t);
  return n >= 1 && n <= 720 ? { ok: true, valor: n } : { ok: false };
}
