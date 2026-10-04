"use client";

// O envio dos anexos, UM A UM, depois que o ato deu certo (a rota de anexo pede o protocolo/a resposta já gravados). Serve ao
// balcão (a secretaria anexa à resposta) e ao portal (a cidadã anexa ao pedido): cada tela diz COMO enviar UM arquivo; aqui
// fica a fila, o resultado de cada um e o "tentar de novo" só do que falhou. Nada de envio em paralelo: a ordem é a escolhida,
// e o limite de 5 (decidido pelo servidor, numa corrida) não é disputado por requisições simultâneas da mesma pessoa.

import { useState } from "react";
import type { ItemDeEnvio } from "./anexos-do-atendimento";

type ResultadoDoEnvio = { ok: true } | { ok: false; mensagem: string };

export function useEnvioDeAnexos(enviarUm: (arquivo: File) => Promise<ResultadoDoEnvio>, aoTerminar?: () => void) {
  const [itens, setItens] = useState<ItemDeEnvio[]>([]);

  async function rodar(lista: ItemDeEnvio[], indices: number[]) {
    for (const i of indices) {
      setItens((xs) => xs.map((x, j) => (j === i ? { ...x, fase: "enviando", mensagem: undefined } : x)));
      const r = await enviarUm(lista[i].arquivo);
      setItens((xs) =>
        xs.map((x, j) => (j === i ? (r.ok ? { ...x, fase: "ok", mensagem: undefined } : { ...x, fase: "erro", mensagem: r.mensagem }) : x)),
      );
    }
    aoTerminar?.();
  }

  /** Começa um envio novo (substitui o resultado anterior): os arquivos sobem na ordem escolhida. */
  async function enviar(arquivos: File[]) {
    if (arquivos.length === 0) return;
    const lista: ItemDeEnvio[] = arquivos.map((arquivo) => ({ arquivo, fase: "esperando" }));
    setItens(lista);
    await rodar(lista, lista.map((_, i) => i));
  }

  /** Tenta de novo SÓ o arquivo `indice` (o que falhou). */
  async function tentarDeNovo(indice: number) {
    await rodar(itens, [indice]);
  }

  const enviando = itens.some((i) => i.fase === "esperando" || i.fase === "enviando");
  return { itens, enviar, tentarDeNovo, enviando };
}
