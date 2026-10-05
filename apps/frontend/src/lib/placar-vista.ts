// View-model PURO do placar: traduz o `PlacarVotacao` do reducer no que a UI deve mostrar — é aqui que mora
// a regra de SIGILO §22.6 no cliente. A secreta NUNCA produz grade nominal (estruturalmente: o kind "secreta"
// não tem campo `votos`); só na NOMINAL com prova de modalidade o cliente revela quem votou o quê. Testado em
// placar-vista.test.ts; o componente React só mapeia o resultado (JSX fino, sem lógica).

import { identidadeDe, type EstadoPlenario, type PlacarVotacao, type VotoNominal } from "./plenario-reducer";

export interface VistaNominal {
  kind: "nominal";
  objetoTipo: string | null;
  encerrada: boolean;
  resultado: string | null;
  sim: number;
  nao: number;
  abstencao: number;
  faltam: number | null; // base − votos apurados (null sem base de membros)
  baseMembros: number | null;
  votos: { vereadorId: string; voto: VotoNominal }[]; // quem votou o quê (público na nominal)
  votosParciais: boolean; // a grade local não bate com o agregado oficial (ex.: pós-reconexão) — UI avisa
  /** Frente 'truncamento-familia' sítio (d): o canal SSE teve uma lacuna (entrada corrompida no replay
   * do backplane) desde que esta página conectou. O placar não tem NENHUM caminho de re-busca (é só o
   * agregado de eventos ao vivo — ao contrário de quórum/tribuna, que se auto-curam em segundos), então
   * a UI precisa admitir que este número pode estar incompleto em vez de mostrá-lo com confiança total. */
  avisoLacuna: boolean;
}

export interface VistaSecreta {
  kind: "secreta";
  objetoTipo: string | null;
  encerrada: boolean;
  resultado: string | null;
  registrados: number; // contador anônimo de votos lançados (§22.6 — sem identidade)
  faltam: number | null;
  baseMembros: number | null;
  totais: { sim: number; nao: number; abstencao: number } | null; // público SÓ no encerramento
  /** Ver a docstring de `VistaNominal.avisoLacuna` — mesmo racional, aqui pro contador anônimo. */
  avisoLacuna: boolean;
}

export type VistaPlacar = { kind: "nenhuma" } | VistaNominal | VistaSecreta;

const conta = (votos: Record<string, VotoNominal>, alvo: VotoNominal): number =>
  Object.values(votos).filter((v) => v === alvo).length;

const RESULTADO_PERMITIDO = new Set(["aprovada", "rejeitada"]);
// resultado vem do servidor e é interpolado em className (.placar-resultado.<resultado>): só os valores do
// contrato passam; qualquer outra coisa vira null (não vira token de classe inesperado). review seg LOW-1.
const resultadoSeguro = (r: string | null): string | null => (r !== null && RESULTADO_PERMITIDO.has(r) ? r : null);

export function derivarPlacar(placar: PlacarVotacao | null, avisoLacuna = false): VistaPlacar {
  if (!placar) return { kind: "nenhuma" };

  const { objetoTipo, encerrada, baseMembros, totais } = placar;
  const resultado = resultadoSeguro(placar.resultado);

  // NOMINAL exige prova de modalidade. Sem ela (ex.: reconexão que só viu o encerramento, modalidade ""),
  // o cliente NÃO revela grade nominal — fail-closed a favor do sigilo, tratando como agregado secreto.
  if (placar.modalidade === "nominal") {
    // no encerramento, o agregado oficial manda; durante a votação, deriva-se da grade local.
    const usarTotais = encerrada && totais !== null;
    const sim = usarTotais ? (totais!.sim ?? 0) : conta(placar.votosNominais, "sim");
    const nao = usarTotais ? (totais!.nao ?? 0) : conta(placar.votosNominais, "nao");
    const abstencao = usarTotais ? (totais!.abstencao ?? 0) : conta(placar.votosNominais, "abstencao");
    const apurados = sim + nao + abstencao;
    const votos = Object.entries(placar.votosNominais)
      .map(([vereadorId, voto]) => ({ vereadorId, voto }))
      .sort((a, b) => a.vereadorId.localeCompare(b.vereadorId));
    return {
      kind: "nominal",
      objetoTipo,
      encerrada,
      resultado,
      sim,
      nao,
      abstencao,
      faltam: baseMembros !== null ? Math.max(0, baseMembros - apurados) : null,
      baseMembros,
      votos,
      // grade local incompleta perante o agregado oficial (ex.: reconexão que não viu todos os votos): o Tally
      // mostra a verdade do servidor, e a UI avisa que a lista nominal está parcial em vez de mentir por omissão.
      votosParciais: usarTotais && votos.length !== apurados,
      avisoLacuna,
    };
  }

  // SECRETA (ou modalidade desconhecida): só o contador; os totais agregados ficam públicos no encerramento.
  const registrados = encerrada && totais !== null ? (totais.sim ?? 0) + (totais.nao ?? 0) + (totais.abstencao ?? 0) : placar.votosSecretos;
  return {
    kind: "secreta",
    objetoTipo,
    encerrada,
    resultado,
    registrados,
    faltam: baseMembros !== null ? Math.max(0, baseMembros - registrados) : null,
    baseMembros,
    totais: encerrada && totais !== null ? { sim: totais.sim ?? 0, nao: totais.nao ?? 0, abstencao: totais.abstencao ?? 0 } : null,
    avisoLacuna,
  };
}

/** O rótulo neutro de quem votou e ainda não tem nome resolvido: a composição não chegou (corrida de carga),
 * a busca dela falhou, ou o id não é de membro da Casa. Nunca o prefixo do uuid — um id truncado LÊ como
 * identidade, e era essa leitura falsa o defeito do telão (04/10/2026). Fonte única: TV e telão da Mesa. */
export const VEREADOR_SEM_NOME = "Vereador(a)";

export interface VotoRotulado {
  vereadorId: string;
  nome: string;
  voto: VotoNominal;
}

/** A grade nominal COM o nome parlamentar, resolvido pelo índice de `GET /sessoes/:id/composicao` que
 * `usePlenario` já hidrata (o evento SSE e o snapshot de votação trazem só o `vereador-id`). Ordem alfabética
 * por nome (pt-BR), quem não tem nome por último — a ordem por uuid, que a grade tinha, não significa nada
 * para quem lê. Só se chama com os `votos` de uma VistaNominal: a secreta não tem grade (sigilo §22.6). */
export function rotularVotos(votos: { vereadorId: string; voto: VotoNominal }[], estado: EstadoPlenario): VotoRotulado[] {
  const rotulados = votos.map(({ vereadorId, voto }) => ({
    vereadorId,
    nome: identidadeDe(estado, vereadorId)?.nomeParlamentar ?? VEREADOR_SEM_NOME,
    voto,
  }));
  return rotulados.sort((a, b) => {
    const aSem = a.nome === VEREADOR_SEM_NOME;
    if (aSem !== (b.nome === VEREADOR_SEM_NOME)) return aSem ? 1 : -1;
    return a.nome.localeCompare(b.nome, "pt-BR") || a.vereadorId.localeCompare(b.vereadorId);
  });
}
