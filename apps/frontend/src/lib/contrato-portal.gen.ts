// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.

export interface NormaOut {
  normaId: string;
  proposicaoId: string;
  tipoNorma: string;
  numero: number;
  ano: number;
  urn: string;
  ementa: string;
  publicadoEm: string;
  veiculoPublicacao: string;
}

export interface NormasOut {
  normas: NormaOut[];
  normasTotal: number;
}

export interface MateriaOut {
  proposicaoId: string;
  tipo: string;
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
  autorTipo?: string | null;
  autorTexto?: string | null;
  estado: string;
}

export interface MateriasOut {
  materias: MateriaOut[];
  materiasTotal: number;
}

export interface ResumoPublicoOut {
  texto: string;
  versao: number;
  geradoComIa: boolean;
  publicadoEm: string;
}

export interface FichaOut {
  proposicaoId: string;
  tipo: string;
  ano: number;
  sequencial: number;
  urnLex: string;
  ementa: string;
  autorTipo?: string | null;
  autorTexto?: string | null;
  estado: string;
  norma?: NormaOut | null;
  resumo?: ResumoPublicoOut | null;
}

export interface EncarregadoOut {
  nome: string;
  rotulo: string;
  email: string;
}

export interface AcompanhamentoEsicOut {
  protocolo: string;
  estado: "em_analise" | "indeferido" | "protocolado" | "respondido";
  diasRestantes: number | null;
}

export interface AcompanhamentoOuvidoriaOut {
  protocolo: string;
  estado: "arquivada" | "em_analise" | "protocolada" | "respondida";
  diasRestantes: number | null;
}

export interface LegislaturaOut {
  numero: number;
  anoInicio: number;
  anoFim: number;
}

export interface MateriaDeAutoriaOut {
  proposicaoId: string;
  tipo: string;
  ano: number;
  sequencial: number;
  ementa: string;
  estado: string;
}

export interface VotoPublicoOut {
  votacaoId: string;
  voto: string;
  ocorridoEm: string;
  materiaRotulo: string | null;
  materiaEmenta: string | null;
}

export interface VotosPorOpcaoOut {
  sim: number;
  nao: number;
  abstencao: number;
}

export interface PresencaOut {
  sessoesPresente: number;
  sessoesComChamada: number;
  janelaDeExercicioConhecida: boolean;
  janelaAnteriorAProjecao: boolean;
}

export interface PerfilVereadorOut {
  vereadorId: string;
  nomeParlamentar: string | null;
  nomeCivil: string;
  legislatura: LegislaturaOut | null;
  cargoMesa: string | null;
  comissoes: string[];
  materias: MateriaDeAutoriaOut[];
  materiasTotal: number;
  normasDeAutoria: number;
  votos: VotoPublicoOut[];
  votosTotal: number;
  votosPorOpcao: VotosPorOpcaoOut;
  presenca: PresencaOut;
  acervoComEloDeAutoriaDesde: string;
  presencaProjetadaDesde: string;
}

export interface ColunaDadosAbertosOut {
  nome: string;
  descricao: string;
}

export interface DatasetAbertoOut {
  chave: string;
  titulo: string;
  descricao: string;
  arquivo: string;
  formato: "csv";
  linhas: number;
  atualizadoEm?: string | null;
  colunas: ColunaDadosAbertosOut[];
}

export interface DadosAbertosOut {
  datasets: DatasetAbertoOut[];
}

export interface SessaoDaVotacaoOut {
  sessaoId: string;
  tipoSessao: string;
  numeroSequencial: number;
  data?: string | null;
}

export interface MateriaDaVotacaoOut {
  proposicaoId: string;
  tipo: string;
  sequencial: number;
  ano: number;
  ementa: string;
}

export interface PlacarOut {
  sim: number;
  nao: number;
  abstencoes: number;
  baseMembros?: number | null;
}

export interface VotacaoPublicaOut {
  votacaoId: string;
  encerradaEm: string;
  sessao: SessaoDaVotacaoOut;
  objetoTipo: string;
  materia?: MateriaDaVotacaoOut | null;
  modalidade: "nominal" | "simbolica" | "secreta";
  quorumTipo: string;
  resultado: "aprovada" | "rejeitada";
  placar?: PlacarOut | null;
}

export interface VotacoesPublicasOut {
  votacoes: VotacaoPublicaOut[];
  total: number;
  pagina: number;
  porPagina: number;
}

export interface VotoNominalOut {
  vereadorId: string;
  vereador: string;
  voto: "sim" | "nao" | "abstencao";
}

export interface VotacaoDetalheOut {
  votacaoId: string;
  encerradaEm: string;
  sessao: SessaoDaVotacaoOut;
  objetoTipo: string;
  materia?: MateriaDaVotacaoOut | null;
  modalidade: "nominal" | "simbolica" | "secreta";
  quorumTipo: string;
  resultado: "aprovada" | "rejeitada";
  placar?: PlacarOut | null;
  votos: VotoNominalOut[];
}
