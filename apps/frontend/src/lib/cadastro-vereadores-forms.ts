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
  // O check de NaN acima barra overflow de mês/formato (mês 13 vira Invalid Date). O round-trip
  // abaixo barra overflow de DIA: `new Date` normaliza silenciosamente (2025-02-30 -> 2025-03-02),
  // então exigimos igualdade de string p/ pegar essa normalização silenciosa.
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
  // "Mudou" = campo PRESENTE no PATCH (mesmo contrato `contains?` do backend adapters/in), não "não-branco":
  // esvaziar o nome parlamentar (limpar o apelido) É uma alteração válida — mandá-lo como "" limpa o campo.
  // Só o `nome` (obrigatório) não pode ser esvaziado.
  const mudouNome = v.nome !== undefined;
  const mudouParlamentar = v.nomeParlamentar !== undefined;
  if (!mudouNome && !mudouParlamentar) erros.geral = "Preencha ao menos um campo para salvar.";
  if (mudouNome && branco(v.nome)) erros.nome = "O nome não pode ficar em branco.";
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

// Conceder acesso (Onda D Slice 5, Task 11) — CPF e e-mail institucional. `apenasDigitos` é exportado
// porque o form precisa da MESMA normalização antes de enviar o corpo (o backend exige `^\d{11}$`
// estrito, sem pontuação — identidade/wire/in/acesso.clj `CriarIdentidade`); validar e enviar têm que
// concordar sobre o que é "o CPF", senão um valor passa na validação do form e falha no POST.
export function apenasDigitos(s: string): string {
  return s.replace(/\D/g, "");
}

// Dígito verificador (mesmo algoritmo do backend, identidade/models/identidade.clj `valido-cpf?`): pega
// erro de digitação óbvio ANTES do round-trip que gastaria o passo 1 (criar identidade) à toa. O
// cliente NUNCA é a única autoridade — o servidor revalida do mesmo jeito.
function digitoVerificador(digitos: number[], pesos: number[]): number {
  const soma = digitos.reduce((acc, d, i) => acc + d * pesos[i], 0);
  const resto = soma % 11;
  return resto < 2 ? 0 : 11 - resto;
}

function cpfValido(cpfBruto: string): boolean {
  const d = apenasDigitos(cpfBruto);
  if (!/^\d{11}$/.test(d)) return false;
  if (/^(\d)\1{10}$/.test(d)) return false; // 11 dígitos iguais: formato passa, CPF não existe
  const digitos = d.split("").map(Number);
  return (
    digitos[9] === digitoVerificador(digitos.slice(0, 9), [10, 9, 8, 7, 6, 5, 4, 3, 2]) &&
    digitos[10] === digitoVerificador(digitos.slice(0, 10), [11, 10, 9, 8, 7, 6, 5, 4, 3, 2])
  );
}

function emailValido(email: string): boolean {
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim());
}

export function validarConcederAcesso(v: { cpf: string; email: string }): Resultado<{ cpf?: string; email?: string }> {
  const erros: { cpf?: string; email?: string } = {};
  if (branco(v.cpf) || !cpfValido(v.cpf)) erros.cpf = "CPF inválido.";
  if (branco(v.email) || !emailValido(v.email)) erros.email = "E-mail inválido.";
  return fechar(erros);
}
