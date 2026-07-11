"use client";

// Sub-rota "Modelos" do Expediente (Onda B Slice 6) — CRUD de modelo de documento (criar/editar corpo do
// template, desativar) ainda não tem tela própria nesta fatia; o backend já tem o modelo de dados e a
// leitura (GET /legislativo/documento-modelos, consumida pelo seletor da aba "Gerar documento"), mas não
// escrita. Mesma disciplina de EmBreve honesto.

import { EmBreve } from "@/lib/em-breve";
import { TopoInterno } from "../../topo";
import { AbasExpediente } from "../abas-expediente";
import "../expediente.css";

export default function PaginaExpedienteModelos() {
  return (
    <>
      <TopoInterno area="Expediente" ator={{ nome: "Ana Ribeiro", papel: "Secretária Legislativa" }} />
      <AbasExpediente atual="modelos" />
      <main className="envelope">
        <div className="expediente-em-breve-wrap">
          <EmBreve
            titulo="Modelos de documento"
            motivo="Criar um modelo novo, editar o corpo do template ou desativar um modelo existente ainda não tem tela própria nesta fatia — só a leitura dos modelos ativos existe (o seletor da aba 'Gerar documento')."
          />
        </div>
      </main>
    </>
  );
}
