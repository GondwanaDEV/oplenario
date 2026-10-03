"use client";

// Rota /comunicados/novo (interno) — escrever um comunicado (ADR-0020). `?substitui=<id>` abre o formulário como
// SUBSTITUTO de um comunicado já enviado (Eixo 5: o enviado não muda; corrige-se com outro). Sem guard de papel: quem
// pode enviar é o backend (`GET /comunicados/destinos` responde 403 a quem não pode, e a tela diz isso).

import { useSearchParams } from "next/navigation";
import { TopoInterno } from "../../topo";
import { EscreverComunicado } from "./escrever-comunicado";
import "../../../comunicacao.css";

export default function PaginaNovoComunicado() {
  const substitui = useSearchParams().get("substitui");
  return (
    <>
      <TopoInterno area="Caixa" />
      <main className="envelope com-pagina">
        <header className="com-pagina-cab">
          <p className="eyebrow">Comunicado interno</p>
          <h1>{substitui ? "Corrigir um comunicado" : "Escrever comunicado"}</h1>
          <p className="com-dica">
            O comunicado vai para a caixa de cada destinatário dentro do sistema, com número de protocolo. Você acompanha
            quem recebeu, quem leu e, se pedir, quem deu ciência.
          </p>
        </header>
        <EscreverComunicado substituiId={substitui} />
      </main>
    </>
  );
}
