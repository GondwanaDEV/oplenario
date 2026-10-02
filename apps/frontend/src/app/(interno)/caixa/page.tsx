"use client";

// Rota /caixa (interno) — a caixa de TODA pessoa interna da Casa (ADR-0020, Eixo 7): comunicados e avisos do sistema
// numa lista só. Sem guard de papel de propósito: a caixa é de qualquer pessoa da Casa (servidor, vereador, jurídico,
// controle interno, administração), inclusive do servidor que não tem papel nenhum além do vínculo — que o FE não
// distingue de um visitante pelos papéis. Quem decide é o backend (o cidadão leva 403, e a tela diz isso em frase).

import { CaixaDaCasa } from "../../caixa-da-casa";
import { TopoInterno } from "../topo";

const hrefComunicado = (id: string) => `/comunicados/${encodeURIComponent(id)}`;

export default function PaginaCaixa() {
  return (
    <>
      <TopoInterno area="Caixa" />
      <main className="envelope cx-pagina">
        <CaixaDaCasa superficie="interno" hrefComunicado={hrefComunicado} />
      </main>
    </>
  );
}
