"use client";

// Acesso ao PARECER JURÍDICO (ADR-0019, fatia 1): a fila de pedidos (GET /api/legislativo/pedidos-parecer-juridico),
// um pedido, e as mutações — criar pedido, cancelar, salvar o rascunho, assinar e substituir. A authz real é o backend
// (leem `secretario` e `juridico`; só o `juridico` escreve e assina; só o `secretario` pede e cancela): aqui só se
// traduz cada resposta em frase honesta. Uma rota que falha vira erro na tela, nunca uma lista vazia fingindo.

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import { corpoDoPedido, corpoDoRascunho, mensagemDeErroJuridico, type AcaoJuridica, type CamposDoParecer, type EntradaPedido } from "./juridico-vista";
import type { EstadoPedido, ParametrosParecerJuridicoOut, PedidoJuridicoOut, PedidosJuridicosOut } from "./contrato-juridico.gen";

export type Resultado<T> = { ok: true; dado: T } | { ok: false; status: number; mensagem: string };

export type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro"; status: number; mensagem: string };

const BASE = "/api/legislativo/pedidos-parecer-juridico";

/** Uma chamada autenticada. Só o 400 carrega o `erro` do servidor para a tela; os demais status têm frase própria. */
export async function pedirJuridico<T>(
  token: string | null,
  caminho: string,
  acao: AcaoJuridica,
  init: { method?: string; corpo?: unknown } = {},
  valido: (dado: unknown) => boolean = () => true,
): Promise<Resultado<T>> {
  if (semCredencial(token)) return { ok: false, status: 401, mensagem: mensagemDeErroJuridico(401, acao) };
  try {
    const r = await apiFetch(caminho, {
      token: token ?? undefined,
      cache: "no-store",
      method: init.method,
      ...(init.corpo !== undefined
        ? { headers: { "Content-Type": "application/json" }, body: JSON.stringify(init.corpo) }
        : {}),
    });
    const corpo = await r.json().catch(() => ({}));
    if (!r.ok) {
      return { ok: false, status: r.status, mensagem: mensagemDeErroJuridico(r.status, acao, (corpo as { erro?: string })?.erro) };
    }
    const dado = camelizarChaves(corpo);
    if (!valido(dado)) return { ok: false, status: r.status, mensagem: mensagemDeErroJuridico(500, acao) };
    return { ok: true, dado: dado as T };
  } catch {
    return { ok: false, status: 0, mensagem: mensagemDeErroJuridico(0, acao) };
  }
}

/** Carrega um GET e o refaz sob demanda SEM piscar (`recarregar` mantém o que já está na tela até chegar o novo). */
export function useCarregarJuridico<T>(
  token: string | null,
  caminho: string | null,
  acao: AcaoJuridica,
  valido?: (dado: unknown) => boolean,
) {
  // o resultado guarda DE QUAL caminho veio: trocar de aba mostra "carregando" até a resposta nova chegar, sem
  // setState síncrono no efeito
  const [carga, setCarga] = useState<{ caminho: string | null; estado: Carga<T> }>({
    caminho: null,
    estado: { fase: "carregando" },
  });
  const [rev, setRev] = useState(0);
  useEffect(() => {
    if (!caminho || semCredencial(token)) return;
    let vivo = true;
    (async () => {
      const r = await pedirJuridico<T>(token, caminho, acao, {}, valido);
      if (vivo)
        setCarga({
          caminho,
          estado: r.ok ? { fase: "pronto", dado: r.dado } : { fase: "erro", status: r.status, mensagem: r.mensagem },
        });
    })();
    return () => {
      vivo = false;
    };
    // `valido` e `acao` são constantes do chamador; refazer só por token, caminho ou pedido explícito
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, caminho, rev]);
  const estado: Carga<T> = !caminho
    ? { fase: "carregando" }
    : semCredencial(token)
      ? { fase: "erro", status: 401, mensagem: mensagemDeErroJuridico(401, acao) }
      : carga.caminho === caminho
        ? carga.estado
        : { fase: "carregando" };
  const setEstado = useCallback((e: Carga<T>) => setCarga({ caminho, estado: e }), [caminho]);
  const recarregar = useCallback(() => setRev((n) => n + 1), []);
  return { estado, setEstado, recarregar };
}

const listaValida = (d: unknown) => Array.isArray((d as PedidosJuridicosOut)?.pedidos);
const pedidoValido = (d: unknown) => typeof (d as PedidoJuridicoOut)?.id === "string" && typeof (d as PedidoJuridicoOut)?.estado === "string";

export function usePedidosJuridicos(token: string | null, estado: EstadoPedido) {
  return useCarregarJuridico<PedidosJuridicosOut>(token, `${BASE}?estado=${estado}`, "listar", listaValida);
}

export function usePedidoJuridico(token: string | null, id: string | null) {
  return useCarregarJuridico<PedidoJuridicoOut>(token, id ? `${BASE}/${encodeURIComponent(id)}` : null, "abrir", pedidoValido);
}

// ---- mutações (todas devolvem o Pedido inteiro, já no estado novo) ----

export function criarPedido(token: string | null, entrada: EntradaPedido, proposicaoId?: string) {
  return pedirJuridico<PedidoJuridicoOut>(token, BASE, "pedir", { method: "POST", corpo: corpoDoPedido(entrada, proposicaoId) }, pedidoValido);
}

export function cancelarPedido(token: string | null, id: string) {
  return pedirJuridico<PedidoJuridicoOut>(token, `${BASE}/${encodeURIComponent(id)}/cancelamento`, "cancelar", { method: "POST", corpo: {} }, pedidoValido);
}

export function salvarRascunho(token: string | null, id: string, campos: CamposDoParecer) {
  return pedirJuridico<PedidoJuridicoOut>(token, `${BASE}/${encodeURIComponent(id)}/parecer`, "salvar", { method: "PUT", corpo: corpoDoRascunho(campos) }, pedidoValido);
}

export function assinarParecer(token: string | null, id: string) {
  return pedirJuridico<PedidoJuridicoOut>(token, `${BASE}/${encodeURIComponent(id)}/parecer/assinatura`, "assinar", { method: "POST", corpo: {} }, pedidoValido);
}

export function substituirParecer(token: string | null, id: string) {
  return pedirJuridico<PedidoJuridicoOut>(token, `${BASE}/${encodeURIComponent(id)}/parecer/substituicao`, "substituir", { method: "POST", corpo: {} }, pedidoValido);
}

// ---- ADR-0019 fatia 2a ----

/** "Usar como rascunho" (papel `juridico`): a nota técnica da IA abre o pedido e o rascunho do parecer numa transação só.
 *  Devolve o pedido já com o rascunho; a tela leva o advogado a `/juridico/[id]`. */
export function usarNotaComoRascunho(token: string | null, notaId: string) {
  return pedirJuridico<PedidoJuridicoOut>(
    token,
    `/api/legislativo/notas-tecnicas/${encodeURIComponent(notaId)}/rascunho-juridico`,
    "usar-nota",
    { method: "POST", corpo: {} },
    pedidoValido,
  );
}

const PARAMETROS = "/api/legislativo/parametros-parecer-juridico";
const parametrosValidos = (d: unknown) => typeof (d as ParametrosParecerJuridicoOut)?.publicarAoAssinar === "boolean";

/** O parâmetro da Casa (`admin_ente`): o parecer vai ao portal ao assinar, ou só depois da deliberação. */
export function useParametrosParecerJuridico(token: string | null) {
  return useCarregarJuridico<ParametrosParecerJuridicoOut>(token, PARAMETROS, "parametros", parametrosValidos);
}

export function salvarParametrosParecerJuridico(token: string | null, publicarAoAssinar: boolean) {
  return pedirJuridico<ParametrosParecerJuridicoOut>(
    token,
    PARAMETROS,
    "salvar-parametros",
    { method: "PUT", corpo: { "publicar-ao-assinar": publicarAoAssinar } },
    parametrosValidos,
  );
}
