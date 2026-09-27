// Lógica pura da FILA DE GRAVAÇÕES (Faixa A / A.2 da Track IA). O arquivo gravado pelo OBS chega pelo utilitário
// de captação SEM sessão; a secretaria confere e vincula. Aqui só se decide o TEXTO: de quando é a gravação,
// quanto dura, de onde veio e qual sessão o horário sugere. Nunca o id cru na tela.

import type { GravacaoPendenteOut, SessaoOut, SugestaoSessaoOut } from "./contrato-sessoes.gen";
import { formatarData, formatarHora } from "./formatar-data";
import { nomeTipoSessao } from "./rotulos-sessao";

const NOME_FONTE: Record<string, string> = {
  gravacao_local_pos_sessao: "Gravação local",
  rtmp_duplicado_ao_vivo: "Transmissão duplicada",
  youtube_api_fallback: "Cópia do YouTube",
  importacao_legado: "Arquivo histórico",
};

export function nomeFonte(fonte: string): string {
  return NOME_FONTE[fonte] ?? fonte;
}

/** "22/09/2026, 17:50". */
export function quando(iso: string): string {
  return `${formatarData(iso)}, ${formatarHora(iso)}`;
}

/** "3 h 10 min", "42 min" — ou null quando a gravação não diz quando terminou, ou diz algo impossível (fim antes
 *  do início, ou mais de um dia de gravação: arquivo copiado depois, relógio errado). */
export function duracao(inicioIso: string, fimIso: string | null | undefined): string | null {
  if (!fimIso) return null;
  const min = Math.round((new Date(fimIso).getTime() - new Date(inicioIso).getTime()) / 60000);
  if (!Number.isFinite(min) || min < 0 || min > 24 * 60) return null;
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60);
  const resto = min % 60;
  return resto === 0 ? `${h} h` : `${h} h ${resto} min`;
}

type SessaoRotulavel = Pick<SugestaoSessaoOut, "tipoSessao" | "numeroSequencial"> & { inicio?: string | null };

/** "Sessão ordinária nº 12 · 22/09/2026, 18:00". */
export function rotuloSessao(s: SessaoRotulavel): string {
  const base = `Sessão ${nomeTipoSessao(s.tipoSessao)} nº ${s.numeroSequencial}`;
  return s.inicio ? `${base} · ${quando(s.inicio)}` : base;
}

/** As sessões que podem receber uma gravação, mais recentes primeiro (a que não aconteceu não entra). */
export function sessoesVinculaveis(sessoes: SessaoOut[]): Array<{ id: string; rotulo: string }> {
  return sessoes
    .filter((s) => s.estado !== "nao_realizada")
    .map((s) => ({ s, inicio: s.abertaEm ?? s.agendadaPara ?? null }))
    .sort((a, b) => (b.inicio ?? "").localeCompare(a.inicio ?? ""))
    .map(({ s, inicio }) => ({ id: s.id, rotulo: rotuloSessao({ ...s, inicio }) }));
}

export type VistaGravacao = {
  id: string;
  lockVersion: number;
  titulo: string;
  detalhe: string;
  restrita: boolean;
  sugestao: { sessaoId: string; rotulo: string } | null;
};

export function vistaGravacao(g: GravacaoPendenteOut): VistaGravacao {
  const d = duracao(g.iniciouEm, g.encerrouEm);
  return {
    id: g.id,
    lockVersion: g.lockVersion,
    titulo: `Gravação de ${quando(g.iniciouEm)}`,
    detalhe: [d, nomeFonte(g.fonteIngestao)].filter(Boolean).join(" · "),
    restrita: g.acessoRestrito,
    sugestao: g.sugestao ? { sessaoId: g.sugestao.sessaoId, rotulo: rotuloSessao(g.sugestao) } : null,
  };
}
