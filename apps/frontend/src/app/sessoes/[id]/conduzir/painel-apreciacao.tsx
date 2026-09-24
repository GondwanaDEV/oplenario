"use client";

// "Em apreciação" no Comando da Mesa (docs/23 Fatia 4b): a lista da pauta com UM botão por item — Anunciar. É
// o ato da Mesa de passar a apreciar a matéria ("passamos ao PL 22/2026"): grava o fato na sessão e a TV do
// plenário muda sozinha para a matéria em destaque, com a autoria. Não é um controle da TV (decisão 4 do
// docs/23): a TV reage ao fato, como reage à votação e à tribuna.
//
// Só com a sessão ABERTA (o servidor recusa nos outros estados). O item em apreciação vem da própria pauta
// (`em-apreciacao`), que o chamador recarrega depois de cada anúncio.

import { useState } from "react";
import type { PautaOut } from "@/lib/contrato";
import { itensDaPautaTv } from "@/lib/tv-vista";
import { useAnunciarItem } from "@/lib/use-anunciar-item";

type Aviso = { tom: "ok" | "erro"; texto: string } | null;

export function PainelApreciacao({
  sessaoId,
  token,
  pauta,
  onAnunciado,
}: {
  sessaoId: string;
  token: string | null;
  pauta: PautaOut;
  onAnunciado: () => void;
}) {
  const { anunciar, enviando } = useAnunciarItem(sessaoId, token);
  const [aviso, setAviso] = useState<Aviso>(null);
  const emApreciacao = pauta["em-apreciacao"]?.["item-id"] ?? null;
  const itens = itensDaPautaTv(pauta, null, emApreciacao);

  async function clicar(id: string, sigla: string) {
    setAviso(null);
    const r = await anunciar(id);
    if (r.ok) {
      setAviso({ tom: "ok", texto: `${sigla} em apreciação — a TV do plenário já mostra a matéria.` });
      onAnunciado();
    } else {
      setAviso({ tom: "erro", texto: r.erro });
    }
  }

  return (
    <div className="apreciacao-mesa">
      <h3 className="apr-tit">Em apreciação</h3>
      <p className="apr-ajuda">
        Anuncie a matéria quando a Mesa passar a apreciá-la. A TV do plenário mostra a matéria, a autoria e quem
        está na tribuna até a votação abrir.
      </p>
      {aviso && (
        <p className={`apr-aviso apr-aviso--${aviso.tom}`} role={aviso.tom === "erro" ? "alert" : "status"}>
          {aviso.texto}
        </p>
      )}
      <ol className="apr-lista">
        {itens.map((it) => (
          <li key={it.id} className={it.emApreciacao ? "apr-atual" : undefined}>
            <span className="apr-ord">{it.ordem}</span>
            <span className="apr-it">
              <b>{it.sigla}</b>
              <span>{it.descricao}</span>
            </span>
            {it.emApreciacao ? (
              <span className="apr-selo">Em apreciação</span>
            ) : (
              <button
                type="button"
                className="btn btn-contorno btn-mini"
                disabled={enviando}
                aria-label={`Anunciar ${it.sigla}`}
                onClick={() => void clicar(it.id, it.sigla)}
              >
                Anunciar
              </button>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
}
