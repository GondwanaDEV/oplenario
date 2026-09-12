"use client";

// Sub-rota "Protocolo geral" do Expediente (Onda B Slice 6) — gestão dedicada do Protocolo Geral (buscar
// por número/período, corrigir descrição, ver o objeto de origem) ainda não tem tela própria nesta fatia; o
// Livro do dia já aparece embutido na aba "Gerar documento" (ver ../tabela-protocolo.tsx). Mesma disciplina
// de EmBreve honesto usada em ficha-materia-tabs.tsx/portal-cidadao.

import { EmBreve } from "@/lib/em-breve";
import { TopoInterno } from "../../topo";
import { AbasExpediente } from "../abas-expediente";
import "../expediente.css";

export default function PaginaExpedienteProtocolo() {
  return (
    <>
      <TopoInterno area="Expediente" />
      <AbasExpediente atual="protocolo" />
      <main className="envelope">
        <div className="expediente-em-breve-wrap">
          <EmBreve
            titulo="Protocolo geral"
            motivo="Buscar por número/período, corrigir descrição ou reabrir o objeto de origem de uma entrada do Protocolo Geral ainda não tem tela própria nesta fatia. O Livro do ano corrente já aparece embutido na aba 'Gerar documento'."
          />
        </div>
      </main>
    </>
  );
}
