"use client";

// Sub-rota "Recebidos" do Expediente (Onda B Slice 6) — a caixa de entrada de correspondência recebida
// (ofícios de outros órgãos, requerimentos de cidadão via e-SIC/balcão físico) ainda não tem contrato de
// backend nenhum nesta fatia (nem leitura) — diferente de Protocolo geral/Modelos, que ao menos têm uma
// leitura real em produção. Mesma disciplina de EmBreve honesto.

import { EmBreve } from "@/lib/em-breve";
import { TopoInterno } from "../../topo";
import { AbasExpediente } from "../abas-expediente";
import "../expediente.css";

export default function PaginaExpedienteRecebidos() {
  return (
    <>
      <TopoInterno area="Expediente" />
      <AbasExpediente atual="recebidos" />
      <main className="envelope">
        <div className="expediente-em-breve-wrap">
          <EmBreve
            titulo="Recebidos"
            motivo="A caixa de entrada de correspondência recebida de outros órgãos ou cidadãos ainda não tem contrato de backend nesta fatia."
          />
        </div>
      </main>
    </>
  );
}
