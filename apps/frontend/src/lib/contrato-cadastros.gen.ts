// GERADO por oplenario.codegen.malli-ts a partir dos models Malli — NAO editar a mao.

export interface VereadorLinhaOut {
  id: string;
  nome: string;
  nomeParlamentar?: string | null;
  partido?: string | null;
  estadoMandato?: string | null;
  cargoMesa?: string | null;
}

export interface ComissaoDoVereadorOut {
  nome: string;
  tipo: string;
  cargo?: string | null;
}

export interface MandatoVigenteOut {
  partido?: string | null;
  estado: string;
  natureza: string;
  posse: string;
  legislaturaNumero?: number | null;
  legislaturaAnoInicio?: number | null;
  legislaturaAnoFim?: number | null;
  cargoMesa?: string | null;
}

export interface VereadorFichaOut {
  id: string;
  nome: string;
  nomeParlamentar?: string | null;
  mandato?: MandatoVigenteOut | null;
  comissoes: ComissaoDoVereadorOut[];
}

export interface ListaVereadoresOut {
  vereadores: VereadorLinhaOut[];
}
