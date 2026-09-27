// Lógica pura das PROPOSTAS DE ATO (Faixa B / B.6, ADR-0012): o assistente prepara, a pessoa confirma aqui. Só o
// TEXTO: estado, validade, o aviso de conteúdo de fora (Eixo 4.5), o recibo e os erros.

export type OrigemDeTerceiro = { ferramenta: string; origem: string; referencia: string };

export type PropostaOut = {
  id: string;
  ferramenta: string;
  agente: string;
  titulo: string;
  texto: string;
  ritual: "confirmar" | "assinatura";
  estado: "aguardando" | "executando" | "confirmada" | "recusada" | "expirada";
  contaminadaPor: OrigemDeTerceiro[];
  criadaEm: string;
  expiraEm: string;
  decididaEm: string | null;
  resultado: Record<string, unknown> | null;
  erro: string | null;
  apresentacaoAtual?: { titulo: string; texto: string } | null;
};

const ESTADOS: Record<PropostaOut["estado"], string> = {
  aguardando: "Esperando você",
  executando: "Executando…",
  confirmada: "Confirmada",
  recusada: "Recusada",
  expirada: "Expirou",
};

export function rotuloDoEstado(estado: PropostaOut["estado"]): string {
  return ESTADOS[estado] ?? estado;
}

/** Eixo 4.5: a proposta nasceu numa execução que leu conteúdo de terceiro (e-SIC, ouvidoria…). */
export function avisoDeOrigem(p: PropostaOut): string | null {
  if (!p.contaminadaPor?.length) return null;
  const lidos = p.contaminadaPor.map((c) => `${c.origem} ${c.referencia}`).join("; ");
  return `Feita depois de ler conteúdo de fora da Casa (${lidos}). Confira com atenção.`;
}

/** O texto é montado de novo ao abrir (a data é a de hoje); se mudou em relação ao proposto, a tela diz. */
export function textoAtualizado(p: PropostaOut): string | null {
  const agora = p.apresentacaoAtual?.texto;
  if (!agora || agora === p.texto) return null;
  return "O texto foi montado de novo agora (a data é a de hoje). É este que será assinado.";
}

const fmt = new Intl.DateTimeFormat("pt-BR", {
  timeZone: "America/Fortaleza",
  day: "2-digit",
  month: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
});

export function validadeDaProposta(p: PropostaOut): string {
  const partes = Object.fromEntries(fmt.formatToParts(new Date(p.expiraEm)).map((x) => [x.type, x.value]));
  return `Vale até ${partes.day}/${partes.month} às ${partes.hour}:${partes.minute}.`;
}

export function reciboDaProposta(p: PropostaOut): string | null {
  const r = p.resultado as { ano?: number; sequencial?: number } | null;
  if (p.estado !== "confirmada" || !r) return null;
  if (p.ferramenta === "protocolar_requerimento" && r.sequencial && r.ano) {
    return `Requerimento nº ${r.sequencial}/${r.ano} protocolado e assinado.`;
  }
  return "Feito.";
}

export function mensagemDeErroProposta(status: number, causa?: string): string {
  if (status === 409 && causa === "expirada") return "Esta proposta expirou. Peça de novo ao assistente, se ainda quiser.";
  if (status === 409 && causa === "sem-objeto") return "O que a proposta pede não existe mais (o modelo, a matéria…). Recuse-a.";
  if (status === 409) return "Esta proposta já foi decidida. Recarregue a página.";
  if (status === 400) return "O texto não fecha: falta algum campo que o modelo pede. Recuse e peça de novo.";
  if (status === 403) return "Você não tem permissão para esta ação agora.";
  if (status === 404) return "Proposta não encontrada.";
  return "Não foi possível falar com o sistema agora. Tente de novo em instantes.";
}
