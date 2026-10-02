"use client";

// Acesso aos COMUNICADOS INTERNOS da Casa (ADR-0020): a caixa, um comunicado, o painel de leitura, os enviados, as
// opções do formulário e as escritas (enviar, anexar, dar ciência). Mesmo desenho de use-juridico.ts: uma chamada
// autenticada (`pedirComunicacao`) que traduz cada status em frase da tela, e um carregador (`useCarregarComunicacao`)
// que refaz sob demanda sem piscar. A authz real é o backend (quem pode enviar a grupo, quem vê a leitura): aqui só se
// diz a recusa em português. Uma rota que falha vira erro na tela, nunca uma lista vazia fingindo.
//
// A caixa JUNTA comunicados e avisos do sistema NA TELA (Eixo 7 — donos diferentes, sem junção no banco), então os
// avisos (`GET /meu/notificacoes`, de `paineis`) entram por aqui também, com uma diferença: 403 nos avisos vira caixa
// de avisos VAZIA, não erro — quem não tem a projeção de avisos não tem aviso, e a caixa dos comunicados segue de pé.
//
// O contador do topo (`useContagemDaCaixa`) escuta `EVENTO_CAIXA_MUDOU`: a tela que lê, marca ou dá ciência avisa, e o
// número do topo se refaz sem recarregar a página.

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type { MinhasNotificacoesOut } from "./contrato-paineis.gen";
import {
  CAMINHO_CONTAGEM_DA_CAIXA,
  CAMPO_DO_ANEXO,
  ROTAS_COMUNICACAO as R,
  corpoDoNovoComunicado,
  formaValida,
  type AnexoOut,
  type CaixaOut,
  type CienciaOut,
  type ComunicadoOut,
  type DestinosOut,
  type EnviadosOut,
  type EnvioOut,
  type LeituraOut,
  type NovoComunicadoIn,
} from "./contrato-comunicacao";

export type Resultado<T> = { ok: true; dado: T } | { ok: false; status: number; mensagem: string };

export type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro"; status: number; mensagem: string };

export type AcaoComunicacao =
  | "caixa"
  | "avisos"
  | "abrir"
  | "leitura"
  | "enviados"
  | "destinos"
  | "enviar"
  | "anexar"
  | "baixar"
  | "ciencia"
  | "setores"
  | "criar-setor"
  | "salvar-setor"
  | "membros-setor";

const RECUSA: Partial<Record<AcaoComunicacao, string>> = {
  caixa: "Sua sessão não dá acesso à caixa da Casa.",
  abrir: "Este comunicado não foi endereçado a você.",
  leitura: "Quem leu cada comunicado só é visível para quem o enviou, para a secretaria e para a administração da Casa.",
  enviados: "Sua sessão não dá acesso aos comunicados enviados.",
  destinos: "Seu acesso não permite enviar comunicados.",
  enviar: "Seu acesso não permite enviar a estes destinos. Setores, comissões e todos os setores são da secretaria, da administração e da Mesa.",
  anexar: "Só quem enviou o comunicado anexa arquivos, e só nos primeiros 10 minutos depois do envio.",
  baixar: "Sua sessão não dá acesso a este anexo.",
  ciencia: "Só quem recebeu o comunicado registra ciência.",
  setores: "Os setores são mantidos pelo administrador da Casa.",
  "criar-setor": "Os setores são mantidos pelo administrador da Casa.",
  "salvar-setor": "Os setores são mantidos pelo administrador da Casa.",
  "membros-setor": "Os setores são mantidos pelo administrador da Casa.",
};

const NAO_ENCONTRADO: Partial<Record<AcaoComunicacao, string>> = {
  abrir: "Este comunicado não existe nesta Casa. Confira o endereço.",
  leitura: "Este comunicado não existe nesta Casa.",
  ciencia: "Este comunicado não existe nesta Casa.",
  anexar: "O comunicado não foi encontrado para receber o anexo.",
  baixar: "O anexo não foi encontrado.",
  "salvar-setor": "Este setor não existe mais. Recarregue a página.",
  "membros-setor": "Este setor não existe mais. Recarregue a página.",
};

function primeiraMaiuscula(s: string) {
  const t = s.trim();
  if (!t) return t;
  const frase = t.charAt(0).toUpperCase() + t.slice(1);
  return /[.!?]$/.test(frase) ? frase : `${frase}.`;
}

/** A frase da tela para cada falha. `erroDoServidor` só aparece onde ele explica algo que a pessoa pode corrigir. */
export function mensagemDeErroComunicacao(status: number, acao: AcaoComunicacao, erroDoServidor?: string): string {
  if (status === 0) return "Sem conexão com o servidor. Verifique a rede e tente de novo.";
  if (status === 401) return "Sua sessão expirou. Entre de novo para continuar.";
  if (status === 403) return RECUSA[acao] ?? "Seu acesso não permite esta ação.";
  if (status === 404) return NAO_ENCONTRADO[acao] ?? "Não encontrado.";
  if (status === 409 && acao === "criar-setor") return "Já existe um setor com esse nome nesta Casa.";
  if (status === 409 && acao === "salvar-setor") return "Já existe outro setor com esse nome nesta Casa.";
  if (status === 410) return "Esta Câmara encerrou o uso do sistema. O acervo fica só para consulta.";
  if (status === 413) return "O arquivo passa de 10 MB, o limite por anexo.";
  if (status === 423) return "A Câmara está com acesso restrito: enviar comunicados está suspenso por enquanto.";
  if ((status === 400 || status === 409 || status === 422) && erroDoServidor) return primeiraMaiuscula(erroDoServidor);
  if (status === 422 && acao === "enviar") {
    return "A lista de destinatários ficou vazia: os destinos escolhidos não têm ninguém com acesso ao sistema.";
  }
  if (status === 400 || status === 422) return "Algum dado não passou na conferência do servidor. Revise e tente de novo.";
  return "O servidor não conseguiu concluir agora. Tente de novo em instantes.";
}

type Init = { method?: string; corpo?: unknown; arquivo?: { campo: string; arquivo: File } };

/** Uma chamada autenticada. JSON por padrão; `arquivo` vira multipart (sem Content-Type: o navegador põe o boundary). */
export async function pedirComunicacao<T>(
  token: string | null,
  caminho: string,
  acao: AcaoComunicacao,
  init: Init = {},
  valido: (dado: unknown) => boolean = () => true,
): Promise<Resultado<T>> {
  if (semCredencial(token)) return { ok: false, status: 401, mensagem: mensagemDeErroComunicacao(401, acao) };
  try {
    let extra: RequestInit = {};
    if (init.arquivo) {
      const fd = new FormData();
      fd.append(init.arquivo.campo, init.arquivo.arquivo, init.arquivo.arquivo.name);
      extra = { body: fd };
    } else if (init.corpo !== undefined) {
      extra = { headers: { "Content-Type": "application/json" }, body: JSON.stringify(init.corpo) };
    }
    const r = await apiFetch(caminho, { token: token ?? undefined, cache: "no-store", method: init.method, ...extra });
    const corpo: unknown = await r.json().catch(() => ({}));
    if (!r.ok) {
      const erro = (corpo as { erro?: unknown })?.erro;
      return { ok: false, status: r.status, mensagem: mensagemDeErroComunicacao(r.status, acao, typeof erro === "string" ? erro : undefined) };
    }
    const dado = camelizarChaves(corpo);
    if (!valido(dado)) return { ok: false, status: 500, mensagem: mensagemDeErroComunicacao(500, acao) };
    return { ok: true, dado: dado as T };
  } catch {
    return { ok: false, status: 0, mensagem: mensagemDeErroComunicacao(0, acao) };
  }
}

/** Carrega um GET e o refaz sob demanda SEM piscar (`recarregar` mantém o que já está na tela até chegar o novo).
 *  `transformar403` (só os avisos usam) troca a recusa por um valor — ver o cabeçalho. */
export function useCarregarComunicacao<T>(
  token: string | null,
  caminho: string | null,
  acao: AcaoComunicacao,
  valido?: (dado: unknown) => boolean,
  transformar403?: () => T,
) {
  const [carga, setCarga] = useState<{ caminho: string | null; estado: Carga<T> }>({ caminho: null, estado: { fase: "carregando" } });
  const [rev, setRev] = useState(0);
  useEffect(() => {
    if (!caminho || semCredencial(token)) return;
    let vivo = true;
    (async () => {
      const r = await pedirComunicacao<T>(token, caminho, acao, {}, valido);
      if (!vivo) return;
      if (r.ok) setCarga({ caminho, estado: { fase: "pronto", dado: r.dado } });
      else if (r.status === 403 && transformar403) setCarga({ caminho, estado: { fase: "pronto", dado: transformar403() } });
      else setCarga({ caminho, estado: { fase: "erro", status: r.status, mensagem: r.mensagem } });
    })();
    return () => {
      vivo = false;
    };
    // `acao`, `valido` e `transformar403` são constantes do chamador; refazer só por token, caminho ou pedido explícito
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, caminho, rev]);
  const estado: Carga<T> = !caminho
    ? { fase: "carregando" }
    : semCredencial(token)
      ? { fase: "erro", status: 401, mensagem: mensagemDeErroComunicacao(401, acao) }
      : carga.caminho === caminho
        ? carga.estado
        : { fase: "carregando" };
  const setEstado = useCallback((e: Carga<T>) => setCarga({ caminho, estado: e }), [caminho]);
  const recarregar = useCallback(() => setRev((n) => n + 1), []);
  return { estado, setEstado, recarregar };
}

// ---- leituras ----

export function useCaixaDeComunicados(token: string | null) {
  return useCarregarComunicacao<CaixaOut>(token, R.caixa, "caixa", formaValida.caixa);
}

const SEM_AVISOS = (): MinhasNotificacoesOut => ({ notificacoes: [], naoLidas: 0, notificacoesTotal: 0 });

/** Os avisos do sistema. 403 = esta pessoa não tem projeção de avisos: caixa de avisos vazia, não erro. */
export function useAvisosDoSistema(token: string | null) {
  return useCarregarComunicacao<MinhasNotificacoesOut>(token, R.avisos, "avisos", formaValida.avisos, SEM_AVISOS);
}

export function useComunicado(token: string | null, id: string | null) {
  return useCarregarComunicacao<ComunicadoOut>(token, id ? R.comunicado(id) : null, "abrir", formaValida.comunicado);
}

/** O painel de leitura. `id` nulo = não buscar (quem não pode ver a leitura nem pede). */
export function useLeituraDoComunicado(token: string | null, id: string | null) {
  return useCarregarComunicacao<LeituraOut>(token, id ? R.leitura(id) : null, "leitura", formaValida.leitura);
}

export function useEnviados(token: string | null, escopo: "meus" | "casa") {
  return useCarregarComunicacao<EnviadosOut>(token, R.enviados(escopo), "enviados", formaValida.enviados);
}

export function useDestinos(token: string | null) {
  return useCarregarComunicacao<DestinosOut>(token, R.destinos, "destinos", formaValida.destinos);
}

// ---- escritas ----

export function enviarComunicado(token: string | null, entrada: NovoComunicadoIn) {
  return pedirComunicacao<EnvioOut>(token, R.enviar, "enviar", { method: "POST", corpo: corpoDoNovoComunicado(entrada) }, formaValida.envio);
}

/** Um anexo por chamada, DEPOIS do 201 (a rota pede o comunicado já existente). A resposta não é lida. */
export function enviarAnexo(token: string | null, comunicadoId: string, arquivo: File) {
  return pedirComunicacao<unknown>(token, R.anexos(comunicadoId), "anexar", { method: "POST", arquivo: { campo: CAMPO_DO_ANEXO, arquivo } });
}

export function registrarCiencia(token: string | null, id: string) {
  return pedirComunicacao<CienciaOut>(token, R.ciencia(id), "ciencia", { method: "POST", corpo: {} }, formaValida.ciencia);
}

/** Modo DEV (token no header): o link cru não leva o `Authorization`, então baixa pelos bytes e dispara o download
 *  aqui (mesma escolha de use-exportacao.ts). No modo real a tela usa um link direto — o cookie vai junto. */
export async function baixarAnexoComToken(token: string, comunicadoId: string, anexo: AnexoOut): Promise<Resultado<null>> {
  let blob: Blob;
  try {
    const r = await apiFetch(R.anexo(comunicadoId, anexo.id), { token, cache: "no-store" });
    if (!r.ok) return { ok: false, status: r.status, mensagem: mensagemDeErroComunicacao(r.status, "baixar") };
    blob = await r.blob();
  } catch {
    return { ok: false, status: 0, mensagem: mensagemDeErroComunicacao(0, "baixar") };
  }
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement("a");
    a.href = url;
    a.download = anexo.nome;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
  return { ok: true, dado: null };
}

// ---- o número do topo ----

export const EVENTO_CAIXA_MUDOU = "oplenario:caixa-mudou";

/** Avisa o contador do topo que algo foi lido, marcado ou reconhecido. */
export function avisarCaixaMudou() {
  if (typeof window !== "undefined") window.dispatchEvent(new Event(EVENTO_CAIXA_MUDOU));
}

async function contar<T>(token: string | null, caminho: string, valido: (d: unknown) => boolean, ler: (d: T) => number) {
  try {
    const r = await apiFetch(caminho, { token: token ?? undefined, cache: "no-store" });
    if (!r.ok) return null;
    const d = camelizarChaves(await r.json());
    if (!valido(d)) return null;
    const n = ler(d as T);
    return Number.isFinite(n) && n >= 0 ? n : null;
  } catch {
    return null;
  }
}

/** Quantos estão por ler: comunicados não lidos + avisos do sistema não lidos. `null` = não deu para saber (nenhuma
 *  das duas fontes respondeu) — o topo então não mostra número nenhum, em vez de um zero que mente. Uma fonte só
 *  respondendo conta só ela: melhor o número dos comunicados que nenhum. */
export function useContagemDaCaixa(token: string | null): number | null {
  const [n, setN] = useState<number | null>(null);
  const [rev, setRev] = useState(0);
  useEffect(() => {
    const refazer = () => setRev((v) => v + 1);
    window.addEventListener(EVENTO_CAIXA_MUDOU, refazer);
    return () => window.removeEventListener(EVENTO_CAIXA_MUDOU, refazer);
  }, []);
  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      const [comunicados, avisos] = await Promise.all([
        contar<CaixaOut>(token, CAMINHO_CONTAGEM_DA_CAIXA, formaValida.caixa, (d) => d.naoLidos),
        contar<MinhasNotificacoesOut>(token, R.avisos, formaValida.avisos, (d) => d.naoLidas),
      ]);
      if (!vivo) return;
      setN(comunicados === null && avisos === null ? null : (comunicados ?? 0) + (avisos ?? 0));
    })();
    return () => {
      vivo = false;
    };
  }, [token, rev]);
  return semCredencial(token) ? null : n;
}
