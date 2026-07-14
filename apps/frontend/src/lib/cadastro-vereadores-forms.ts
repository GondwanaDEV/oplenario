// View-models puros de VALIDAÇÃO dos forms de escrita do cadastro de vereadores (Onda D Slice 4). Sem
// DOM/fetch — só regras de campo + mensagens pt-BR. Mesma disciplina de cadastro-vereadores-vista.ts.
// A validação de data espelha o backend (adapters/in: AAAA-MM-DD -> LocalDate/parse); aqui só barramos
// o formato/consistência antes do POST (o servidor revalida — o FE nunca é a única autoridade).

function branco(s: string | null | undefined): boolean {
  return s == null || s.trim() === "";
}

// AAAA-MM-DD estrito + data real (rejeita "2025-13-40"). Retorna o Date UTC ou null.
function dataISO(s: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) return null;
  const d = new Date(`${s}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return null;
  // round-trip guard: `new Date` normaliza overflow (13 -> jan do ano seguinte); exigimos igualdade.
  return d.toISOString().slice(0, 10) === s ? d : null;
}

export type Resultado<E> = { erros: E; valido: boolean };
function fechar<E extends object>(erros: E): Resultado<E> {
  return { erros, valido: Object.keys(erros).length === 0 };
}

export function validarNovoVereador(v: { nome: string }): Resultado<{ nome?: string }> {
  const erros: { nome?: string } = {};
  if (branco(v.nome)) erros.nome = "Informe o nome do vereador.";
  return fechar(erros);
}

export function validarEditar(v: { nome?: string; nomeParlamentar?: string }): Resultado<{ nome?: string; geral?: string }> {
  const erros: { nome?: string; geral?: string } = {};
  const temNome = !branco(v.nome);
  const temParlamentar = !branco(v.nomeParlamentar);
  if (!temNome && !temParlamentar) erros.geral = "Preencha ao menos um campo para salvar.";
  if (v.nome !== undefined && v.nome !== "" && branco(v.nome)) erros.nome = "O nome não pode ficar em branco.";
  return fechar(erros);
}

export function validarMandato(v: {
  legislaturaId: string; natureza: string; vigenciaInicio: string; vigenciaFim?: string;
}): Resultado<{ legislaturaId?: string; natureza?: string; vigenciaInicio?: string; vigenciaFim?: string }> {
  const erros: { legislaturaId?: string; natureza?: string; vigenciaInicio?: string; vigenciaFim?: string } = {};
  if (branco(v.legislaturaId)) erros.legislaturaId = "Selecione a legislatura.";
  if (v.natureza !== "titular" && v.natureza !== "suplencia") erros.natureza = "Selecione a natureza do mandato.";
  const ini = dataISO(v.vigenciaInicio);
  if (!ini) erros.vigenciaInicio = "Data de início inválida (AAAA-MM-DD).";
  if (!branco(v.vigenciaFim)) {
    const fim = dataISO(v.vigenciaFim as string);
    if (!fim) erros.vigenciaFim = "Data de fim inválida (AAAA-MM-DD).";
    else if (ini && fim < ini) erros.vigenciaFim = "O fim não pode ser anterior ao início.";
  }
  return fechar(erros);
}

export function validarLicenca(v: { inicio: string; fim?: string }): Resultado<{ inicio?: string; fim?: string }> {
  const erros: { inicio?: string; fim?: string } = {};
  const ini = dataISO(v.inicio);
  if (!ini) erros.inicio = "Data de início inválida (AAAA-MM-DD).";
  if (!branco(v.fim)) {
    const fim = dataISO(v.fim as string);
    if (!fim) erros.fim = "Data de fim inválida (AAAA-MM-DD).";
    else if (ini && fim < ini) erros.fim = "O fim não pode ser anterior ao início.";
  }
  return fechar(erros);
}
