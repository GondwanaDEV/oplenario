// View-model PURO do cockpit de voto do vereador: deriva se o botão de votar deve estar habilitado, qual
// voto (se algum) já foi registrado para ESTE vereador na votação corrente, e se a própria presença já foi
// confirmada nesta sessão — tudo a partir do MESMO EstadoPlenario que alimenta o placar da Mesa (nenhum
// estado paralelo: o cockpit lê o placar OFICIAL). Testado em meu-voto-vista.test.ts.

import type { EstadoPlenario, VotoNominal } from "./plenario-reducer";

export type CicloVoto = "sem-votacao" | "pode-votar" | "ja-votou" | "secreta" | "encerrada" | "sem-presenca";

export interface VistaMeuVoto {
  ciclo: CicloVoto;
  meuVoto: VotoNominal | null; // preenchido só quando ciclo === "ja-votou"
  presente: boolean;
}

export function derivarMeuVoto(estado: EstadoPlenario | null, meuVereadorId: string | null): VistaMeuVoto {
  const presente = !!meuVereadorId && !!estado && estado.presentes.includes(meuVereadorId);

  if (!estado || !estado.placar) {
    return { ciclo: "sem-votacao", meuVoto: null, presente };
  }
  const { placar } = estado;

  if (placar.encerrada) {
    return { ciclo: "encerrada", meuVoto: null, presente };
  }
  if (placar.modalidade !== "nominal") {
    // secreta (ou modalidade ainda não provada, ex.: reconexão) — mesma disciplina fail-closed do placar-vista:
    // sem prova de nominal, o cockpit NUNCA oferece o botão de voto pelo celular (só a Mesa/terminal registra).
    return { ciclo: "secreta", meuVoto: null, presente };
  }
  const meuVoto = meuVereadorId ? (placar.votosNominais[meuVereadorId] ?? null) : null;
  if (meuVoto) {
    return { ciclo: "ja-votou", meuVoto, presente };
  }
  if (!presente) {
    return { ciclo: "sem-presenca", meuVoto: null, presente };
  }
  return { ciclo: "pode-votar", meuVoto: null, presente };
}
