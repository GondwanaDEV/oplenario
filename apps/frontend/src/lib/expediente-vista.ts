// View-model puro do Expediente/Protocolo Geral (Onda B Slice 6) — traduz DocumentoOut/DocumentoModeloOut/
// ProtocoloGeralOut (wire, camelizado) pro que a aba "Gerar documento"
// (produto/design-system/o-plenario/telas/expediente.html) mostra. Mesma disciplina de parecer-vista.ts:
// funções puras, sem React/fetch, fail-closed pra vocabulário fora do esperado (nunca inventa, nunca lança).
//
// Badge de tipo do Livro do Protocolo Geral: o vocabulário REAL do backend nesse ponto é `objetoTipo` do
// PROTOCOLO (wire/in enum `objetos-protocolo`: proposicao/documento/oficio_recebido/requerimento_cidadao/
// processo_administrativo/outro) — NÃO o `tipoDocumento` do documento gerado (oficio/certidao/
// requerimento_administrativo/convite/mala_direta/outro). Um documento gerado por esta aba sempre entra no
// livro como objetoTipo "documento" (controllers.clj/protocolar-documento fixa isso), então o livro não
// distingue Ofício de Certidão por linha — a paleta do mockup (oficio=cobalto/requerimento=amarelo/
// proposicao=jade/processo=telha) é remapeada aqui para o vocabulário de objetoTipo realmente disponível.

import type { DocumentoOut } from "./contrato-legislativo.gen";

export type CorTipo = "jade" | "cobalto" | "amarelo" | "telha" | "neutro";

const OBJETO_TIPO_ROTULO: Record<string, string> = {
  proposicao: "Proposição",
  documento: "Documento",
  oficio_recebido: "Ofício recebido",
  requerimento_cidadao: "Requerimento",
  processo_administrativo: "Processo administrativo",
  outro: "Outro",
};

const OBJETO_TIPO_COR: Record<string, CorTipo> = {
  proposicao: "jade",
  documento: "cobalto",
  oficio_recebido: "cobalto",
  requerimento_cidadao: "amarelo",
  processo_administrativo: "telha",
  outro: "neutro",
};

export function rotularObjetoTipo(objetoTipo: string): string {
  return OBJETO_TIPO_ROTULO[objetoTipo] ?? objetoTipo; // fail-closed: fora do vocabulário -> valor cru.
}

export function corObjetoTipo(objetoTipo: string): CorTipo {
  return OBJETO_TIPO_COR[objetoTipo] ?? "neutro";
}

export type SentidoVista = { rotulo: string; direcao: "entrada" | "saida" | "interno" };

const SENTIDO_POR_VALOR: Record<string, SentidoVista> = {
  recebido: { rotulo: "Entrada", direcao: "entrada" },
  expedido: { rotulo: "Saída", direcao: "saida" },
  interno: { rotulo: "Interno", direcao: "interno" },
};

export function rotularSentido(sentido: string): SentidoVista {
  return SENTIDO_POR_VALOR[sentido] ?? { rotulo: sentido, direcao: "interno" };
}

// "Nº protocolo" do Livro/carimbo: ano/numero, numero com 5 dígitos (mesma largura do mockup "2026/00847").
export function formatarNumeroProtocolo(numero: number, ano: number): string {
  return `${ano}/${String(numero).padStart(5, "0")}`;
}

const TIPO_DOCUMENTO_ROTULO: Record<string, string> = {
  oficio: "Ofício",
  certidao: "Certidão",
  requerimento_administrativo: "Requerimento",
  convite: "Convite",
  mala_direta: "Mala-direta",
  outro: "Outro",
};

// Fonte única das chaves (mesma ordem do <select> de tipo-documento em modelos/formulario-modelo.tsx) —
// espelha legislativo.logic/tipos-documento (wire/in/documento-modelo.CriarModelo usa o mesmo vocabulário).
export const TIPOS_DOCUMENTO = Object.keys(TIPO_DOCUMENTO_ROTULO);

export function rotularTipoDocumento(tipo: string): string {
  return TIPO_DOCUMENTO_ROTULO[tipo] ?? tipo;
}

const ESTADO_DOCUMENTO_ROTULO: Record<string, string> = {
  rascunho: "Rascunho",
  emitido: "Emitido",
};

export function rotularEstadoDocumento(estado: string): string {
  return ESTADO_DOCUMENTO_ROTULO[estado] ?? estado;
}

export function documentoEhTerminal(estado: string): boolean {
  return estado === "emitido"; // espelha legislativo.logic/estados-documento-terminais.
}

// O carimbo do Protocolo Geral: "a reservar" antes de protocolar, o número real depois — nunca inventa um
// número antes de o backend devolvê-lo (Global Constraint "sem dado falso").
export function textoCarimbo(documento: Pick<DocumentoOut, "protocoloNumero" | "protocoloAno">): string {
  if (documento.protocoloNumero == null || documento.protocoloAno == null) return "a reservar";
  return formatarNumeroProtocolo(documento.protocoloNumero, documento.protocoloAno);
}

function escaparRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export type SegmentoMerge = { texto: string; merge: boolean };

// Destaca no corpo RENDERIZADO (já com o merge aplicado pelo servidor, sem marcação própria) os trechos que
// correspondem EXATAMENTE a algum dos valores que o próprio cliente enviou em `dados` na geração — é o único
// jeito honesto de saber "o que foi mesclado" sem o backend expor posição de placeholder (wire/out/
// documento-modelo.clj recusa expor o corpo-template cru ao cliente). Valores mais longos primeiro (evita um
// valor curto "comer" parte de um valor mais longo que o contém). Vazio -> nenhum destaque.
export function destacarMerge(corpo: string, valoresMerge: string[]): SegmentoMerge[] {
  if (!corpo) return [];
  const valores = Array.from(new Set(valoresMerge.map((v) => v.trim()).filter((v) => v.length > 0))).sort(
    (a, b) => b.length - a.length,
  );
  if (valores.length === 0) return [{ texto: corpo, merge: false }];
  const padrao = new RegExp(`(${valores.map(escaparRegex).join("|")})`, "g");
  return corpo
    .split(padrao)
    .filter((parte) => parte !== "")
    .map((parte) => ({ texto: parte, merge: valores.includes(parte) }));
}

// `dados` (o corpo de POST /legislativo/documentos) é um mapa chave->valor digitado à mão no formulário de
// preenchimento (linhas dinâmicas "campo"/"valor") — linhas com chave em branco são descartadas (não viram
// placeholder vazio no wire).
export function paresParaMapaDados(pares: Array<{ chave: string; valor: string }>): Record<string, string> {
  return Object.fromEntries(
    pares.map(({ chave, valor }) => [chave.trim(), valor]).filter(([chave]) => (chave as string).length > 0),
  );
}
