// Os ANEXOS da resposta (e-SIC, ouvidoria, LGPD): lógica PURA do que a secretaria escolhe antes de enviar e do que a tela
// diz depois. A resposta a um pedido costuma ser um documento: a Casa anexa nos 10 minutos seguintes ao último ato de
// resposta, até 5 arquivos de até 10 MB, em nove formatos. O SERVIDOR confere tudo de novo (e decide a coerência entre o
// tipo declarado e a extensão); aqui só se barra o óbvio antes de gastar um envio, e se escreve o resultado em palavras.

export const LIMITE_DE_ANEXOS = 5;
export const TAMANHO_MAXIMO_DO_ANEXO = 10 * 1024 * 1024;
/** O nome do campo do multipart (`arquivo`), o mesmo do backend e dos comunicados. */
export const CAMPO_DO_ANEXO = "arquivo";

export const EXTENSOES_ACEITAS = ["pdf", "png", "jpg", "jpeg", "txt", "csv", "docx", "xlsx", "odt", "ods"] as const;
export const TIPOS_ACEITOS_EM_TEXTO = "PDF, PNG, JPEG, TXT, CSV, DOCX, XLSX, ODT e ODS";
/** O `accept` do seletor: ajuda o navegador a filtrar; NÃO é a regra (o servidor é). */
export const ACCEPT_DO_SELETOR = EXTENSOES_ACEITAS.map((e) => `.${e}`).join(",");

/** A extensão do nome, em minúsculas e sem o ponto; null se não tem. */
export function extensaoDe(nome: string): string | null {
  const i = nome.lastIndexOf(".");
  return i >= 0 && i < nome.length - 1 ? nome.slice(i + 1).toLowerCase() : null;
}

/** Por que este arquivo não pode ir (tipo fora da lista, vazio, grande demais); null = pode. */
export function motivoDeRecusa(a: { name: string; size: number }): string | null {
  const ext = extensaoDe(a.name);
  if (!ext || !(EXTENSOES_ACEITAS as readonly string[]).includes(ext)) {
    return `“${a.name}” não é de um tipo aceito (${TIPOS_ACEITOS_EM_TEXTO}).`;
  }
  if (a.size === 0) return `“${a.name}” está vazio.`;
  if (a.size > TAMANHO_MAXIMO_DO_ANEXO) return `“${a.name}” passa de 10 MB, o limite por anexo.`;
  return null;
}

/** Junta os arquivos escolhidos aos que já estavam, recusando — com o motivo — o que não pode ir. */
export function adicionarArquivos(atuais: File[], novos: File[]): { arquivos: File[]; recusados: string[] } {
  const arquivos = [...atuais];
  const recusados: string[] = [];
  for (const a of novos) {
    if (arquivos.some((x) => x.name === a.name && x.size === a.size)) continue;
    const motivo = motivoDeRecusa(a);
    if (motivo) {
      recusados.push(motivo);
      continue;
    }
    if (arquivos.length >= LIMITE_DE_ANEXOS) {
      recusados.push(`“${a.name}” não foi incluído: são no máximo ${LIMITE_DE_ANEXOS} anexos.`);
      continue;
    }
    arquivos.push(a);
  }
  return { arquivos, recusados };
}

const ROTULO_DO_TIPO: Record<string, string> = {
  "application/pdf": "PDF",
  "image/png": "PNG",
  "image/jpeg": "JPEG",
  "text/plain": "Texto",
  "text/csv": "CSV",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "Word (DOCX)",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "Excel (XLSX)",
  "application/vnd.oasis.opendocument.text": "Texto (ODT)",
  "application/vnd.oasis.opendocument.spreadsheet": "Planilha (ODS)",
};

/** O formato em palavras (nunca o tipo MIME cru na tela); desconhecido sai como veio. */
export function rotuloDoTipo(tipoMidia: string): string {
  return ROTULO_DO_TIPO[tipoMidia] ?? tipoMidia;
}

export type FaseDoAnexo = "esperando" | "enviando" | "ok" | "erro";
export type ItemDeEnvio = { arquivo: File; fase: FaseDoAnexo; mensagem?: string };

/** Uma frase para o resultado do envio (os arquivos sobem um a um, depois do ato). */
export function resumoDoEnvio(itens: ItemDeEnvio[]): string {
  if (itens.some((i) => i.fase === "esperando" || i.fase === "enviando")) return "Enviando os arquivos…";
  const ok = itens.filter((i) => i.fase === "ok").length;
  const erro = itens.filter((i) => i.fase === "erro").length;
  if (erro === 0) return ok === 1 ? "1 arquivo anexado." : `${ok} arquivos anexados.`;
  if (ok === 0) return itens.length === 1 ? "O arquivo não foi anexado." : "Nenhum arquivo foi anexado.";
  return `${ok === 1 ? "1 arquivo anexado" : `${ok} arquivos anexados`}; ${erro === 1 ? "1 não foi anexado" : `${erro} não foram anexados`}.`;
}

/** O que a cidadã lê quando o anexo ao PRÓPRIO pedido não vai. A frase do servidor explica o 409 e o 415. */
export function mensagemDeErroDoAnexoDoRequerente(status: number, erroDoServidor?: string): string {
  if (status === 0) return "Sem conexão com o servidor. Verifique a rede e tente de novo.";
  if (status === 401) return "Sua sessão terminou. Entre de novo com o gov.br para anexar.";
  if (status === 404) return "Não encontramos este protocolo para receber o anexo.";
  if (status === 413) return "O arquivo passa de 10 MB, o limite por anexo.";
  if (status === 415) return erroDoServidor || `Tipo de arquivo não aceito. Aceitamos ${TIPOS_ACEITOS_EM_TEXTO}.`;
  if (status === 409) return erroDoServidor || "Já não dá para anexar: só vale até 10 minutos depois de enviar o pedido, e até 5 arquivos.";
  if (status === 400) return "O arquivo está vazio ou veio malformado. Escolha-o de novo.";
  if (status === 423) return "A Câmara está com acesso restrito por enquanto. Tente de novo mais tarde.";
  return "Não foi possível anexar agora. Tente de novo em instantes.";
}
