"use client";

// Rota /atendimento (interno) — o BALCÃO DE ATENDIMENTO AO CIDADÃO (6.1 e-SIC, 6.2 ouvidoria, 5.10 LGPD). O cidadão pede
// pelo portal; aqui a secretaria vê cada fila pelo prazo que vence primeiro e abre o protocolo para responder. Arquétipo
// fila + detalhe, o mesmo de /juridico e /conferencias. Gate de papel via <GuardSecretaria> (a authz real é o backend:
// exige-papel "secretario" em todas as rotas do balcão).

import { useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { especieValida } from "@/lib/atendimento-vista";
import { GuardSecretaria } from "../guard-secretaria";
import { TopoInterno } from "../topo";
import { BlocoEncarregado } from "./encarregado";
import { FilaAtendimento } from "./fila-atendimento";
import "./atendimento.css";

export default function PaginaAtendimento() {
  return (
    <GuardSecretaria>
      <Conteudo />
    </GuardSecretaria>
  );
}

function Conteudo() {
  const { token } = useAuth();
  const aba = useSearchParams().get("aba");
  return (
    <>
      <TopoInterno area="Atendimento" />
      <main className="envelope atd">
        <header className="atd-cabeca">
          <h1>Atendimento ao cidadão</h1>
          <p className="atd-sub">
            O que as pessoas pediram à Câmara pelo portal: acesso à informação, ouvidoria e dados pessoais. Cada fila
            começa pelo prazo que vence primeiro. Abra o protocolo para ler o pedido e responder.
          </p>
        </header>
        <FilaAtendimento token={token} especieInicial={especieValida(aba) ? aba : "esic"} />
        <BlocoEncarregado token={token} />
      </main>
    </>
  );
}
