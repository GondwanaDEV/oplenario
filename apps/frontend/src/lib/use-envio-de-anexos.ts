"use client";

// O envio dos anexos, UM A UM, depois que o ato deu certo (a rota de anexo pede o protocolo/a resposta já gravados). Serve ao
// balcão (a secretaria anexa à resposta) e ao portal (a cidadã anexa ao pedido): cada tela diz COMO enviar UM arquivo; aqui
// fica a fila, o resultado de cada um e o "tentar de novo" só do que falhou. Nada de envio em paralelo: a ordem é a escolhida,
// e o limite de 5 (decidido pelo servidor, numa corrida) não é disputado por requisições simultâneas da mesma pessoa.
//
// Garantias:
//  - SEM reentrância: `enviar` e `tentarDeNovo` são ignorados enquanto há envio em andamento (dois envios no meio um do outro
//    embaralhariam a fila e o resultado de cada arquivo);
//  - o callback que LANÇA vira erro DO ITEM (frase de gente), a fila segue e `aoTerminar` roda UMA vez no fim;
//  - enquanto envia, o navegador PERGUNTA antes de fechar/recarregar a página (`beforeunload`): sair no meio perderia os
//    arquivos que ainda não subiram, em silêncio. O aviso sai quando o envio acaba e quando o componente desmonta;
//  - desmontar no meio PARA a fila (sem mexer em estado de componente morto) e não chama `aoTerminar`.

import { useCallback, useEffect, useRef, useState } from "react";
import type { ItemDeEnvio } from "./anexos-do-atendimento";

type ResultadoDoEnvio = { ok: true } | { ok: false; mensagem: string };

const FALHA_INESPERADA = "Não foi possível anexar agora. Tente de novo em instantes.";

export function useEnvioDeAnexos(enviarUm: (arquivo: File) => Promise<ResultadoDoEnvio>, aoTerminar?: () => void) {
  const [itens, setItens] = useState<ItemDeEnvio[]>([]);
  const [enviando, setEnviando] = useState(false);
  const ocupado = useRef(false);          // a trava de reentrancia: sincrona (o estado so' muda no proximo render)
  const vivo = useRef(true);
  const itensRef = useRef<ItemDeEnvio[]>([]);
  // sempre o `enviarUm` e o `aoTerminar` da ultima renderizacao (a tela troca de closure a cada render)
  const enviarUmRef = useRef(enviarUm);
  const aoTerminarRef = useRef(aoTerminar);
  useEffect(() => {
    enviarUmRef.current = enviarUm;
    aoTerminarRef.current = aoTerminar;
  });
  useEffect(() => {
    vivo.current = true;   // StrictMode monta, desmonta e monta de novo: o ref nao pode nascer "morto"
    return () => {
      vivo.current = false;
    };
  }, []);

  // o aviso de saida vale SO' enquanto envia
  useEffect(() => {
    if (!enviando) return;
    const avisar = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = "";   // alguns navegadores exigem o valor para mostrar o aviso
    };
    window.addEventListener("beforeunload", avisar);
    return () => window.removeEventListener("beforeunload", avisar);
  }, [enviando]);

  const atualizar = useCallback((f: (xs: ItemDeEnvio[]) => ItemDeEnvio[]) => {
    itensRef.current = f(itensRef.current);
    if (vivo.current) setItens(itensRef.current);
  }, []);

  const rodar = useCallback(async (indices: number[]) => {
    ocupado.current = true;
    setEnviando(true);
    try {
      for (const i of indices) {
        if (!vivo.current) return;   // desmontou: para a fila, sem aoTerminar
        atualizar((xs) => xs.map((x, j) => (j === i ? { ...x, fase: "enviando", mensagem: undefined } : x)));
        let r: ResultadoDoEnvio;
        try {
          r = await enviarUmRef.current(itensRef.current[i].arquivo);
        } catch {
          r = { ok: false, mensagem: FALHA_INESPERADA };   // o callback que lanca e' erro DO ITEM, nao da fila
        }
        if (!vivo.current) return;
        atualizar((xs) =>
          xs.map((x, j) => (j === i ? (r.ok ? { ...x, fase: "ok", mensagem: undefined } : { ...x, fase: "erro", mensagem: r.mensagem }) : x)),
        );
      }
      aoTerminarRef.current?.();
    } finally {
      ocupado.current = false;
      if (vivo.current) setEnviando(false);
    }
  }, [atualizar]);

  /** Começa um envio novo (substitui o resultado anterior): os arquivos sobem na ordem escolhida. Ignorado se já há envio. */
  const enviar = useCallback(async (arquivos: File[]) => {
    if (ocupado.current || arquivos.length === 0) return;
    const lista: ItemDeEnvio[] = arquivos.map((arquivo) => ({ arquivo, fase: "esperando" }));
    itensRef.current = lista;
    setItens(lista);
    await rodar(lista.map((_, i) => i));
  }, [rodar]);

  /** Tenta de novo SÓ o arquivo `indice` (o que falhou). Ignorado se já há envio. */
  const tentarDeNovo = useCallback(async (indice: number) => {
    if (ocupado.current || !itensRef.current[indice]) return;
    await rodar([indice]);
  }, [rodar]);

  return { itens, enviar, tentarDeNovo, enviando };
}
