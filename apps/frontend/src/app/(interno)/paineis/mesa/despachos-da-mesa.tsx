"use client";

// "O que só a Mesa despacha" — porta .fila de paineis-mesa.html. Só "designar relator" tem dado real
// (fila.relator); os demais (distribuição/autógrafo/ata) ficam em-breve por construção (spec §7 — nenhum
// tem rota hoje) e NÃO viram bullet points inventados: aparecem como uma nota honesta, não itens fake.

import { derivarRef } from "@/lib/materia-vista";
import type { MesaVista } from "@/lib/mesa-vista";

export function DespachosDaMesa({ vista }: { vista: MesaVista["despachos"] }) {
  const relatorItens = vista.relator.estado === "disponivel" ? vista.relator.itens : [];
  return (
    <section className="bloco" aria-labelledby="fila-titulo">
      <div className="bloco-cabeca">
        <h2 id="fila-titulo">O que só a Mesa despacha</h2>
        <span className="selo-n mono">{relatorItens.length} item(ns)</span>
      </div>
      <div className="bloco-corpo">
        {vista.relator.estado === "indisponivel" && <p>Fila de relatores indisponível no momento.</p>}
        <ol className="fila">
          {relatorItens.map((it) => (
            <li key={it.id} className="fila-item">
              <div className="fila-txt">
                <span className="tag-p">Designar relator</span>
                <b>{derivarRef(it)}</b>
                <span className="de">{it.ementa}</span>
              </div>
            </li>
          ))}
        </ol>
        {relatorItens.length === 0 && vista.relator.estado === "disponivel" && (
          <p>Nenhum parecer aguardando designação de relator.</p>
        )}
        <p className="nota-gap">
          Despachar distribuição, assinar autógrafo e revisar ata seguem fora deste painel por ora — sem
          rota de backend ainda (carry documentado no spec).
        </p>
      </div>
    </section>
  );
}
