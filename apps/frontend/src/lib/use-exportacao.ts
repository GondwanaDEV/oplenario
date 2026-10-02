"use client";

// ADR-0018 (fatia 2) — a EXPORTAÇÃO completa vista pela Câmara (9.6): o bloco "Exportar os dados da Câmara" da área do
// administrador. GET/POST /api/administracao/exportacoes, o download e a confirmação de recebimento. A authz real é o
// backend (só o `admin_ente` da própria Câmara; a de outra Câmara nem existe); aqui só se traduz cada resposta em frase
// honesta. Enquanto há uma geração em andamento, a leitura se refaz sozinha a cada 5 s.

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "./api-fetch";
import { camelizarChaves } from "./boundary";
import { semCredencial } from "./modo";
import type { Exportacao, ExportacoesDaCasa } from "./contrato-exportacao";

export type Carga<T> = { fase: "carregando" } | { fase: "pronto"; dado: T } | { fase: "erro"; mensagem: string };
export type Resultado<T> = { ok: true; dado: T } | { ok: false; mensagem: string };

const CAMINHO = "/api/administracao/exportacoes";
export const INTERVALO_DA_GERACAO_MS = 5000;

/** As recusas nomeadas do backend (`causa`), em frase de tela. */
const POR_CAUSA: Record<string, string> = {
  "exportacao-em-andamento": "Já há uma exportação sendo gerada. Espere ela terminar.",
  "exportacao-indisponivel": "A exportação completa ainda não está disponível nesta instalação.",
  "fila-cheia": "Muitas exportações na fila agora. Tente de novo em alguns minutos.",
  "ja-confirmada": "O recebimento deste arquivo já foi confirmado.",
  "exportacao-nao-pronta": "Este arquivo ainda não está pronto.",
  "codigo-nao-confere": "O código não confere com o deste arquivo. Recarregue a página e confira de novo.",
  "estado-da-casa": "A Câmara não está num estado que permita exportar.",
  "arquivo-ausente": "O arquivo desta exportação não está mais disponível. Gere uma nova.",
};

function mensagem(status: number, corpo: { causa?: string }): string {
  if (corpo.causa && POR_CAUSA[corpo.causa]) return POR_CAUSA[corpo.causa];
  if (status === 401) return "Sua sessão expirou. Entre de novo.";
  if (status === 403) return "Só o administrador da Câmara exporta os dados.";
  if (status === 404) return "Esta exportação não foi encontrada.";
  if (status === 410) return "Esta Câmara foi encerrada.";
  if (status === 423) return "O sistema da Câmara está com acesso restrito.";
  return "Não foi possível concluir agora. Tente de novo em instantes.";
}

async function chamar<T>(token: string | null, caminho: string, init: { method?: string; corpo?: unknown } = {}): Promise<Resultado<T>> {
  if (semCredencial(token)) return { ok: false, mensagem: "Sua sessão expirou. Entre de novo." };
  try {
    const r = await apiFetch(caminho, {
      token: token ?? undefined,
      cache: "no-store",
      method: init.method,
      ...(init.corpo !== undefined
        ? { headers: { "Content-Type": "application/json" }, body: JSON.stringify(init.corpo) }
        : {}),
    });
    const corpo = (await r.json().catch(() => ({}))) as { causa?: string };
    if (!r.ok) return { ok: false, mensagem: mensagem(r.status, corpo) };
    return { ok: true, dado: camelizarChaves(corpo) as T };
  } catch {
    return { ok: false, mensagem: "Sem conexão com o servidor. Verifique a rede e tente de novo." };
  }
}

export function useExportacoes(token: string | null) {
  const [carga, setCarga] = useState<Carga<ExportacoesDaCasa>>({ fase: "carregando" });
  const [rev, setRev] = useState(0);
  const recarregar = useCallback(() => setRev((v) => v + 1), []);

  useEffect(() => {
    if (semCredencial(token)) return;
    let vivo = true;
    (async () => {
      const r = await chamar<ExportacoesDaCasa>(token, CAMINHO);
      if (vivo) setCarga(r.ok ? { fase: "pronto", dado: r.dado } : { fase: "erro", mensagem: r.mensagem });
    })();
    return () => {
      vivo = false;
    };
  }, [token, rev]);

  // a geração roda em segundo plano no servidor: enquanto houver uma, a tela pergunta de novo
  const gerando = carga.fase === "pronto" && carga.dado.exportacoes.some((e) => e.estado === "gerando");
  useEffect(() => {
    if (!gerando) return;
    const t = setTimeout(recarregar, INTERVALO_DA_GERACAO_MS);
    return () => clearTimeout(t);
  }, [gerando, rev, recarregar]);

  return { carga, recarregar };
}

export function gerarExportacao(token: string | null) {
  return chamar<Exportacao>(token, CAMINHO, { method: "POST", corpo: {} });
}

/** "Recebemos o arquivo com o código …": vai o código que a tela mostrou — o servidor confere que é o deste arquivo. */
export function confirmarRecebimento(token: string | null, e: Exportacao) {
  return chamar<Exportacao>(token, `${CAMINHO}/${encodeURIComponent(e.id)}/confirmacao`, {
    method: "POST",
    corpo: { sha256: e.sha256 },
  });
}

export function caminhoDoArquivo(e: Exportacao): string {
  return `${CAMINHO}/${encodeURIComponent(e.id)}/arquivo`;
}

export function nomeDoArquivo(e: Exportacao): string {
  return `exportacao-completa-${(e.concluidaEm ?? "").slice(0, 10)}-${(e.sha256 ?? "").slice(0, 12)}.zip`;
}

/** Modo DEV (token no header): o link cru não leva o `Authorization`, então baixa pelos bytes e dispara o download
 *  aqui (mesma escolha de `use-folha`). No modo real, a tela usa um link direto — o cookie da sessão vai junto e o
 *  arquivo, que pode ser grande, vai do servidor para o disco sem passar pela memória da página. */
export async function baixarComToken(token: string, e: Exportacao): Promise<Resultado<null>> {
  let blob: Blob;
  try {
    const r = await apiFetch(caminhoDoArquivo(e), { token, cache: "no-store" });
    if (!r.ok) return { ok: false, mensagem: mensagem(r.status, (await r.json().catch(() => ({}))) as { causa?: string }) };
    blob = await r.blob();
  } catch {
    return { ok: false, mensagem: "Sem conexão com o servidor. Verifique a rede e tente de novo." };
  }
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement("a");
    a.href = url;
    a.download = nomeDoArquivo(e);
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
  return { ok: true, dado: null };
}
