"use client";

// A seção "Notas técnicas da IA" da fila do jurídico (ADR-0019 fatia 2a, Eixo 5). Na Casa com jurídico ativo, a nota
// técnica que o agente institucional deixa a cada proposição protocolada chega aqui como RASCUNHO: o advogado a lê (texto
// e citações, a mesma leitura da secretaria) e pode "usar como rascunho" do parecer. A nota continua na fila da
// secretaria enquanto pendente; quem a usar primeiro a tira das duas. Nada aqui é parecer: só o advogado assina.

import Link from "next/link";
import { comToken } from "@/lib/nav";
import { linhaDaNota, numeroDaNota } from "@/lib/conferencias-vista";
import { AVISO_TEXTO_DE_IA } from "@/lib/juridico-vista";
import { useNotasTecnicas } from "@/lib/use-conferencias";
import "../conferencias/conferencias.css";

export function NotasDaIA({ token }: { token: string | null }) {
  const { estado } = useNotasTecnicas(token, "pendente");
  const itens = estado.fase === "pronto" ? estado.dado.itens : [];
  return (
    <section className="jur-notas" aria-labelledby="jur-notas-titulo">
      <h2 id="jur-notas-titulo">Notas técnicas da IA</h2>
      <p className="jur-dica">
        A cada proposição protocolada, a IA confere o texto contra a Lei Orgânica e o Regimento e deixa uma nota em rascunho.
        Leia a nota e, se servir, use-a como ponto de partida do parecer. {AVISO_TEXTO_DE_IA} A secretaria vê a mesma nota:
        quem a usar primeiro a tira das duas filas.
      </p>
      {estado.fase === "carregando" && <p role="status">Carregando as notas…</p>}
      {estado.fase === "erro" && <p className="jur-erro" role="alert">{estado.mensagem}</p>}
      {estado.fase === "pronto" &&
        (itens.length === 0 ? (
          <p className="jur-vazio">Nenhuma nota técnica a conferir. Quando uma proposição for protocolada, a nota da IA aparece aqui.</p>
        ) : (
          <ul className="jur-lista" aria-label="Notas técnicas da IA a conferir">
            {itens.map((n) => (
              <li key={n.id} className="jur-item">
                <div className="jur-item-corpo">
                  <p className="jur-ref">
                    <span className="jur-numero">{numeroDaNota(n)}</span>
                    <span className="jur-chip jur-chip-pendente">Rascunho da IA</span>
                    {n.incerteza === "revisar_com_atencao" && <span className="jur-chip jur-chip-atencao">Ler com atenção</span>}
                  </p>
                  <p className="jur-ementa">{n.ementa}</p>
                  <p className="jur-linha">{linhaDaNota(n)}</p>
                </div>
                <Link className="btn btn-contorno jur-abrir" href={comToken(`/juridico/notas/${n.id}`, token)}
                  aria-label={`Ler a nota técnica: ${numeroDaNota(n)}, ${n.ementa}`}>
                  Ler a nota
                </Link>
              </li>
            ))}
          </ul>
        ))}
    </section>
  );
}
