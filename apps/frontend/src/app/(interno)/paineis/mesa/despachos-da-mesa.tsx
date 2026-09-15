"use client";

// "O que só a Mesa despacha" — porta .fila de paineis-mesa.html. Só "designar relator" tem dado real
// (fila.relator); os demais (distribuição/autógrafo/ata) ficam em-breve por construção (spec §7 — nenhum
// tem rota hoje) e NÃO viram bullet points inventados: aparecem como uma nota honesta, não itens fake.

import { derivarRef } from "@/lib/materia-vista";
import type { MesaVista } from "@/lib/mesa-vista";
import type { RelatorPendenteOut } from "@/lib/contrato-mesa.gen";

// Fatia "truncamento-familia", achado "classe JOIN": `tipo`/`sequencial`/`ano` ficam `[:maybe ...]` no
// contrato (LEFT JOIN sem par possível) — `derivarRef` exige os 3 presentes. `null` aqui SEMPRE coincide
// com `it.indisponivel === true` (o backend só nula o cabeçalho quando o JOIN não achou par), mas o
// guard lê os 3 campos diretamente (não `it.indisponivel`) para o narrowing do TS valer sem cast.
function refDoRelator(it: RelatorPendenteOut): string | null {
  if (it.tipo == null || it.sequencial == null || it.ano == null) return null;
  return derivarRef({ tipo: it.tipo, sequencial: it.sequencial, ano: it.ano });
}

export function DespachosDaMesa({ vista }: { vista: MesaVista["despachos"] }) {
  const relatorItens = vista.relator.estado === "disponivel" ? vista.relator.itens : [];
  return (
    <section className="bloco" aria-labelledby="fila-titulo">
      <div className="bloco-cabeca">
        <h2 id="fila-titulo">O que só a Mesa despacha</h2>
        <span className="selo-n mono">{relatorItens.length}{vista.relator.truncado ? "+" : ""} item(ns)</span>
      </div>
      <div className="bloco-corpo">
        {vista.relator.estado === "indisponivel" && <p>Fila de relatores indisponível no momento.</p>}
        {/* Fatia "truncamento-familia": `truncado` é AUTORITATIVO do servidor (sonda teto+1) — nunca
            deduzido comparando contagens locais. Mesma classe visual de ficha-materia/tramitacao. */}
        {vista.relator.truncado && (
          <p role="status" className="aviso-corte">
            Mostrando os <b>{relatorItens.length}</b> pareceres mais antigos aguardando designação — pode
            haver mais fora desta lista.
          </p>
        )}
        <ol className="fila">
          {relatorItens.map((it) => {
            const ref = refDoRelator(it);
            return (
              <li key={it.id} className="fila-item">
                <div className="fila-txt">
                  <span className="tag-p">Designar relator</span>
                  {ref === null ? (
                    <>
                      <b>Matéria indisponível</b>
                      <span className="de">O cabeçalho desta proposição não pôde ser carregado.</span>
                    </>
                  ) : (
                    <>
                      <b>{ref}</b>
                      <span className="de">{it.ementa}</span>
                    </>
                  )}
                </div>
              </li>
            );
          })}
        </ol>
        {relatorItens.length === 0 && vista.relator.estado === "disponivel" && (
          <p>Nenhum parecer aguardando designação de relator.</p>
        )}
        <p className="nota-gap">
          Despachar a distribuição, assinar o autógrafo e revisar a ata ainda são feitos fora deste painel.
        </p>
      </div>
    </section>
  );
}
