// View-model PURO da tela "Novo requerimento" do vereador (fatia 2a) — sem React, testável isolado. O
// formulário nasce dos CAMPOS que o modelo da Casa declara (os placeholders {{...}} menos os automáticos,
// que o servidor preenche: autor do login e data). Aqui só o que a tela precisa decidir: como rotular um
// campo, se ele é texto corrido, o que falta preencher e como descrever a assinatura sem prometer demais.

/** Rótulos dos placeholders usados nos modelos da demo e nos requerimentos mais comuns. Desconhecido →
 * `rotuloCampo` humaniza a chave (sem inventar acento). */
const ROTULOS: Record<string, string> = {
  destinatario: "Destinatário",
  assunto: "Assunto",
  justificativa: "Justificativa",
  pedido: "O que você requer",
  falecido: "Nome de quem faleceu",
  endereco_familia: "Endereço da família",
};

/** Campos de texto corrido (várias linhas) — o resto é linha única. */
const LONGOS = new Set(["justificativa", "pedido"]);

export function rotuloCampo(chave: string): string {
  if (ROTULOS[chave]) return ROTULOS[chave];
  const s = chave.replace(/[_.-]+/g, " ").trim();
  return s ? s[0].toUpperCase() + s.slice(1) : chave;
}

export function campoLongo(chave: string): boolean {
  return LONGOS.has(chave);
}

/** Os campos do modelo ainda vazios (espaço em branco não conta), na ordem do modelo. */
export function faltando(campos: string[], valores: Record<string, string>): string[] {
  return campos.filter((c) => !(valores[c] ?? "").trim());
}

/** A prévia só faz sentido com modelo escolhido, ementa e todos os campos preenchidos. */
export function podeVerPrevia(f: {
  modeloId: string | null;
  ementa: string;
  campos: string[];
  valores: Record<string, string>;
}): boolean {
  return !!f.modeloId && !!f.ementa.trim() && faltando(f.campos, f.valores).length === 0;
}

/** O selo que o servidor gravou. `STUB-ICP-v0` (o assinador provisório) é dito como tal — a tela não
 * afirma validade ICP-Brasil que a assinatura ainda não tem. */
export function seloDaAssinatura(algoritmo: string): { provisorio: boolean; texto: string } {
  if (algoritmo.startsWith("STUB-")) {
    return {
      provisorio: true,
      texto: "Assinatura eletrônica registrada no sistema (selo provisório — a certificação ICP-Brasil entra numa fase seguinte).",
    };
  }
  return { provisorio: false, texto: `Assinado digitalmente (${algoritmo}).` };
}
