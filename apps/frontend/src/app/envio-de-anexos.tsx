"use client";

// O resultado do envio dos anexos, depois do ato: cada arquivo, um a um, com o que aconteceu. Se algum falhar, a tela diz
// QUAL e POR QUÊ (a frase do servidor) e deixa tentar de novo. No balcão o "tentar de novo" só vale enquanto o servidor
// diz que ainda cabe anexar (a janela de 10 minutos depois do último ato, e até 5 anexos da Casa); no portal ele fica
// sempre à mão e o servidor responde se a janela passou.

import { resumoDoEnvio, type ItemDeEnvio } from "@/lib/anexos-do-atendimento";
import "./anexos-do-atendimento.css";

export function PainelDeEnvio({
  itens,
  podeTentarDeNovo,
  aoTentarDeNovo,
  incerto = false,
  semJanela = "Já não cabe anexar: passaram os 10 minutos depois da resposta, ou a Casa já tem 5 anexos.",
}: {
  itens: ItemDeEnvio[];
  podeTentarDeNovo: boolean;
  aoTentarDeNovo: (indice: number) => void;
  /** NÃO SEI se ainda cabe anexar (a página não conseguiu reler o protocolo): "não sei" não é "não pode". Mostra a falha de
   *  rede, e quem manda em `podeTentarDeNovo` deve deixá-lo ligado. */
  incerto?: boolean;
  /** O que dizer quando houve falha e já não dá para tentar de novo (só quando SE SABE que não cabe). */
  semJanela?: string;
}) {
  if (itens.length === 0) return null;
  const falhou = itens.some((i) => i.fase === "erro");
  const subindo = itens.some((i) => i.fase === "esperando" || i.fase === "enviando");
  return (
    <section className="anx-envio" aria-label="Envio dos anexos">
      {subindo && <p className="anx-nao-feche"><b>Enviando anexos, não feche esta página.</b></p>}
      <p role="status">{resumoDoEnvio(itens)}</p>
      <ul aria-label="Envio dos anexos">
        {itens.map((i, n) => (
          <li key={`${i.arquivo.name}:${n}`}>
            <span className="anx-arquivo-nome">{i.arquivo.name}</span>
            <span className={`anx-arquivo-fase anx-fase-${i.fase}`}>
              {i.fase === "esperando" && "na fila"}
              {i.fase === "enviando" && "enviando…"}
              {i.fase === "ok" && "anexado"}
              {i.fase === "erro" && `não foi anexado: ${i.mensagem ?? "tente de novo"}`}
            </span>
            {i.fase === "erro" && (
              <button
                type="button"
                className="btn btn-contorno btn-mini"
                aria-label={`Tentar de novo o anexo ${i.arquivo.name}`}
                disabled={!podeTentarDeNovo}
                onClick={() => aoTentarDeNovo(n)}
              >
                Tentar de novo
              </button>
            )}
          </li>
        ))}
      </ul>
      {falhou && incerto && (
        <p className="anx-dica">
          Não deu para confirmar se ainda cabe anexar: a página está relendo o protocolo ou não conseguiu (rede ou servidor). Tente de novo.
        </p>
      )}
      {falhou && !incerto && !podeTentarDeNovo && <p className="anx-dica">{semJanela}</p>}
    </section>
  );
}
