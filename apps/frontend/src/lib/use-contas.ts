"use client";

// Acesso ao JULGAMENTO DAS CONTAS (ADR-0021 Parte B): a lista, a ficha, o registro (que protocola o PDL), a notificação,
// os documentos (multipart, campo `arquivo`), o PATCH da Mesa, os prazos por Casa e a pergunta do painel de votação
// ("esta matéria é de contas?"). Mesmo desenho de use-comunicados.ts: uma chamada autenticada (`pedirContas`) que
// traduz cada status em frase da tela, e um carregador (`useCarregarContas`) que refaz sob demanda sem piscar. A authz
// real é o backend; aqui só se diz a recusa em português. Uma rota que falha vira erro na tela, nunca lista vazia.

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import {
  CAMPO_DO_DOCUMENTO,
  ROTAS_CONTAS as R,
  corpoDaNotificacao,
  corpoDaNovaPrestacao,
  corpoDosParametros,
  doFio,
  formaValida,
  type DocumentoContas,
  type ListaPrestacoesOut,
  type NovaPrestacaoIn,
  type ParametrosContas,
  type PrestacaoOut,
  type TipoDocumentoContas,
} from "./contrato-contas";

export type Resultado<T> = { ok: true; dado: T } | { ok: false; status: number; mensagem: string };

export type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro"; status: number; mensagem: string };

export type AcaoContas =
  | "lista"
  | "abrir"
  | "registrar"
  | "atualizar"
  | "notificar"
  | "documento"
  | "baixar"
  | "da-proposicao"
  | "parametros"
  | "salvar-parametros";

const RECUSA: Partial<Record<AcaoContas, string>> = {
  lista: "As contas são acompanhadas pela secretaria, pelos vereadores e pelo jurídico da Casa.",
  abrir: "As contas são acompanhadas pela secretaria, pelos vereadores e pelo jurídico da Casa.",
  registrar: "Só a secretaria registra a prestação de contas.",
  atualizar: "Só a secretaria atualiza o processo no TCE.",
  notificar: "Só a secretaria registra a notificação do responsável.",
  documento: "Só a secretaria junta documentos à prestação.",
  baixar: "Sua sessão não dá acesso a este documento.",
  parametros: "Os prazos das contas são da administração da Casa.",
  "salvar-parametros": "Só o administrador da Casa muda os prazos das contas.",
};

const NAO_ENCONTRADO: Partial<Record<AcaoContas, string>> = {
  abrir: "Esta prestação de contas não existe nesta Casa. Confira o endereço.",
  atualizar: "Esta prestação de contas não existe mais. Recarregue a página.",
  notificar: "Esta prestação de contas não existe mais. Recarregue a página.",
  documento: "Esta prestação de contas não existe mais. Recarregue a página.",
  baixar: "O documento não foi encontrado.",
  registrar: "A comissão escolhida não existe nesta Casa. Escolha outra.",
};

function primeiraMaiuscula(s: string) {
  const t = s.trim();
  if (!t) return t;
  const frase = t.charAt(0).toUpperCase() + t.slice(1);
  return /[.!?]$/.test(frase) ? frase : `${frase}.`;
}

/** A frase da tela para cada falha. `erroDoServidor` só aparece onde explica algo que a pessoa pode corrigir. */
export function mensagemDeErroContas(status: number, acao: AcaoContas, erroDoServidor?: string): string {
  if (status === 0) return "Sem conexão com o servidor. Verifique a rede e tente de novo.";
  if (status === 401) return "Sua sessão expirou. Entre de novo para continuar.";
  if (status === 403) return RECUSA[acao] ?? "Seu acesso não permite esta ação.";
  if (status === 404) return NAO_ENCONTRADO[acao] ?? "Não encontrado.";
  if (status === 409 && acao === "registrar" && !erroDoServidor) return "Já existe uma prestação deste tipo para este exercício nesta Casa.";
  if (status === 410) return "Esta Câmara encerrou o uso do sistema. O acervo fica só para consulta.";
  if (status === 413) return "O arquivo passa de 10 MB, o limite por documento.";
  if (status === 423) return "A Câmara está com acesso restrito: as contas ficam só para consulta por enquanto.";
  if ((status === 400 || status === 409 || status === 422) && erroDoServidor) return primeiraMaiuscula(erroDoServidor);
  if (status === 400 || status === 422) return "Algum dado não passou na conferência do servidor. Revise e tente de novo.";
  if (status === 409) return "A prestação mudou enquanto esta tela estava aberta. Recarregue e confira.";
  return "O servidor não conseguiu concluir agora. Tente de novo em instantes.";
}

type Init = { method?: string; corpo?: unknown; arquivo?: File };

/** Uma chamada autenticada. JSON por padrão; `arquivo` vira multipart (sem Content-Type: o navegador põe o boundary). */
export async function pedirContas<T>(
  token: string | null,
  caminho: string,
  acao: AcaoContas,
  init: Init = {},
  valido: (dado: unknown) => boolean = () => true,
  adaptar: (dado: unknown) => unknown = (d) => d,
): Promise<Resultado<T>> {
  if (semCredencial(token)) return { ok: false, status: 401, mensagem: mensagemDeErroContas(401, acao) };
  try {
    let extra: RequestInit = {};
    if (init.arquivo) {
      const fd = new FormData();
      fd.append(CAMPO_DO_DOCUMENTO, init.arquivo, init.arquivo.name);
      extra = { body: fd };
    } else if (init.corpo !== undefined) {
      extra = { headers: { "Content-Type": "application/json" }, body: JSON.stringify(init.corpo) };
    }
    const r = await apiFetch(caminho, { token: token ?? undefined, cache: "no-store", method: init.method, ...extra });
    const corpo: unknown = await r.json().catch(() => ({}));
    if (!r.ok) {
      const erro = (corpo as { erro?: unknown })?.erro;
      return { ok: false, status: r.status, mensagem: mensagemDeErroContas(r.status, acao, typeof erro === "string" ? erro : undefined) };
    }
    const dado = adaptar(camelizarChaves(corpo));
    if (!valido(dado)) return { ok: false, status: 500, mensagem: mensagemDeErroContas(500, acao) };
    return { ok: true, dado: dado as T };
  } catch {
    return { ok: false, status: 0, mensagem: mensagemDeErroContas(0, acao) };
  }
}

/** Carrega um GET e o refaz sob demanda SEM piscar (`recarregar` mantém o que está na tela até chegar o novo). */
export function useCarregarContas<T>(
  token: string | null,
  caminho: string | null,
  acao: AcaoContas,
  valido?: (dado: unknown) => boolean,
  adaptar?: (dado: unknown) => unknown,
) {
  const [carga, setCarga] = useState<{ caminho: string | null; estado: Carga<T> }>({ caminho: null, estado: { fase: "carregando" } });
  const [rev, setRev] = useState(0);
  useEffect(() => {
    if (!caminho || semCredencial(token)) return;
    let vivo = true;
    (async () => {
      const r = await pedirContas<T>(token, caminho, acao, {}, valido, adaptar);
      if (!vivo) return;
      setCarga({ caminho, estado: r.ok ? { fase: "pronto", dado: r.dado } : { fase: "erro", status: r.status, mensagem: r.mensagem } });
    })();
    return () => {
      vivo = false;
    };
    // `acao`, `valido` e `adaptar` são constantes do chamador; refazer só por token, caminho ou pedido explícito
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, caminho, rev]);
  const estado: Carga<T> = !caminho
    ? { fase: "carregando" }
    : semCredencial(token)
      ? { fase: "erro", status: 401, mensagem: mensagemDeErroContas(401, acao) }
      : carga.caminho === caminho
        ? carga.estado
        : { fase: "carregando" };
  const setEstado = useCallback((e: Carga<T>) => setCarga({ caminho, estado: e }), [caminho]);
  const recarregar = useCallback(() => setRev((n) => n + 1), []);
  return { estado, setEstado, recarregar };
}

// ---- leituras ----

export function useListaContas(token: string | null) {
  return useCarregarContas<ListaPrestacoesOut>(token, R.lista, "lista", formaValida.lista, doFio.lista);
}

export function usePrestacao(token: string | null, id: string | null) {
  return useCarregarContas<PrestacaoOut>(token, id ? R.prestacao(id) : null, "abrir", formaValida.prestacao, doFio.prestacao);
}

export function useParametrosContas(token: string | null) {
  return useCarregarContas<ParametrosContas>(token, R.parametros, "parametros", formaValida.parametros);
}

/** A matéria é de contas? 200 → a prestação; 404 → `null` (matéria comum, comportamento de sempre). */
export async function buscarContasDaProposicao(token: string | null, proposicaoId: string): Promise<Resultado<PrestacaoOut | null>> {
  const r = await pedirContas<PrestacaoOut>(token, R.daProposicao(proposicaoId), "da-proposicao", {}, formaValida.prestacao, doFio.prestacao);
  if (!r.ok && r.status === 404) return { ok: true, dado: null };
  return r;
}

export type MateriaDeContas =
  | { fase: "nenhuma" }
  | { fase: "carregando" }
  /** Matéria comum — ou não deu para saber: o painel segue como sempre e o servidor aplica a guarda de qualquer forma. */
  | { fase: "comum" }
  | { fase: "contas"; prestacao: PrestacaoOut };

/** Para o painel de votação: pergunta a `legislativo` se a matéria escolhida é o PDL de uma prestação de contas. */
export function useContasDaProposicao(token: string | null, proposicaoId: string | null): MateriaDeContas {
  const [r, setR] = useState<{ de: string | null; valor: MateriaDeContas }>({ de: null, valor: { fase: "nenhuma" } });
  useEffect(() => {
    if (!proposicaoId) return;
    let vivo = true;
    (async () => {
      const res = await buscarContasDaProposicao(token, proposicaoId);
      if (!vivo) return;
      setR({ de: proposicaoId, valor: res.ok && res.dado ? { fase: "contas", prestacao: res.dado } : { fase: "comum" } });
    })();
    return () => {
      vivo = false;
    };
  }, [token, proposicaoId]);
  if (!proposicaoId) return { fase: "nenhuma" };
  return r.de === proposicaoId ? r.valor : { fase: "carregando" };
}

// ---- escritas ----

export function registrarPrestacao(token: string | null, e: NovaPrestacaoIn) {
  return pedirContas<PrestacaoOut>(token, R.registrar, "registrar", { method: "POST", corpo: corpoDaNovaPrestacao(e) }, formaValida.prestacao, doFio.prestacao);
}

export function atualizarPrestacao(token: string | null, id: string, campos: { processoTce?: string | null; situacaoTce?: string | null }) {
  const corpo: Record<string, string | null> = {};
  if (campos.processoTce !== undefined) corpo["processo-tce"] = campos.processoTce?.trim() || null;
  if (campos.situacaoTce !== undefined) corpo["situacao-tce"] = campos.situacaoTce?.trim() || null;
  return pedirContas<unknown>(token, R.prestacao(id), "atualizar", { method: "PATCH", corpo });
}

export function registrarNotificacao(token: string | null, id: string, notificadoEm: string, meio: string) {
  return pedirContas<unknown>(token, R.notificacao(id), "notificar", { method: "POST", corpo: corpoDaNotificacao(notificadoEm, meio) });
}

/** Um documento por chamada. `tipo=defesa` marca a defesa juntada (o servidor faz). A resposta não é lida. */
export function enviarDocumento(token: string | null, id: string, tipo: TipoDocumentoContas, arquivo: File) {
  return pedirContas<unknown>(token, R.documentos(id, tipo), "documento", { method: "POST", arquivo });
}

export function salvarParametrosContas(token: string | null, p: { prazoDefesaDias: number; prazoJulgamentoDias: number }) {
  return pedirContas<ParametrosContas>(token, R.parametros, "salvar-parametros", { method: "PUT", corpo: corpoDosParametros(p) }, formaValida.parametros);
}

/** Modo DEV (token no header): o link cru não leva o `Authorization`, então baixa pelos bytes e dispara o download aqui
 *  (mesma escolha de use-comunicados.ts). No modo real a tela usa um link direto — o cookie vai junto. */
export async function baixarDocumentoComToken(token: string, prestacaoId: string, doc: Pick<DocumentoContas, "id" | "nome">): Promise<Resultado<null>> {
  let blob: Blob;
  try {
    const r = await apiFetch(R.documento(prestacaoId, doc.id), { token, cache: "no-store" });
    if (!r.ok) return { ok: false, status: r.status, mensagem: mensagemDeErroContas(r.status, "baixar") };
    blob = await r.blob();
  } catch {
    return { ok: false, status: 0, mensagem: mensagemDeErroContas(0, "baixar") };
  }
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement("a");
    a.href = url;
    a.download = doc.nome;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
  return { ok: true, dado: null };
}
