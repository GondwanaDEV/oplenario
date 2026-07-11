"use client";

// Onda B Slice 5 — editor de parecer de comissão. Espelha a estrutura de editor-proposicao/[id]/page.tsx
// (use(params), loading/erro mirror EXATO — mesmas classes .envelope/.tela-estado, mesmo role="status").
// Diferença: 3 hooks (GET + 2 mutações) em vez de 1, e um estado de "mensagem de status" local (rascunho
// salvo / parecer emitido) que some ao trocar de ação.

import { use, useState } from "react";
import { useAuth } from "@/lib/auth";
import { useParecerEditor } from "@/lib/use-parecer-editor";
import { useSalvarRascunhoParecer } from "@/lib/use-salvar-rascunho-parecer";
import { useEmitirParecer } from "@/lib/use-emitir-parecer";
import { derivarMateria, rotularEstadoParecer, parecerEhTerminal } from "@/lib/parecer-vista";
import { TopoInterno } from "../../topo";
import { FormularioParecer, type ValoresParecer } from "../formulario-parecer";
import { RailParecer } from "../rail-parecer";
import "../parecer.css";

export default function PaginaParecer({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { token } = useAuth();
  const { dados, estado: estadoDetalhe, recarregar } = useParecerEditor(token, id);
  const { salvar, estado: estadoRascunho, erro: erroRascunho } = useSalvarRascunhoParecer(token, id);
  const { emitir, estado: estadoEmissao, erro: erroEmissao } = useEmitirParecer(token, id);
  const [mensagemStatus, setMensagemStatus] = useState<string | null>(null);

  async function aoSalvarRascunho(valores: ValoresParecer) {
    setMensagemStatus(null);
    try {
      await salvar({ relatorio: valores.relatorio, analise: valores.analise });
      setMensagemStatus("Rascunho salvo");
    } catch {
      // erro ja' refletido pelo hook (erroRascunho).
    }
  }

  async function aoEmitir(valores: ValoresParecer) {
    setMensagemStatus(null);
    if (!dados) return;
    try {
      await emitir({ votoRelator: valores.votoRelator, lockVersion: dados.lockVersion });
      // pos-emissao: o backend pode ou nao ter transicionado o estado do parecer (depende do template) —
      // refaz o GET pra saber com certeza, em vez de assumir (spec §"Depois de Emitir").
      await recarregar();
      setMensagemStatus("Parecer emitido");
    } catch {
      // erro ja' refletido pelo hook (erroEmissao).
    }
  }

  if (estadoDetalhe === "carregando") {
    return (
      <>
        <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
        <main className="envelope">
          <p role="status">Carregando…</p>
        </main>
      </>
    );
  }

  if (estadoDetalhe === "erro" || !dados) {
    return (
      <>
        <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
        <main className="tela-estado">
          <h1>Não foi possível carregar este parecer</h1>
        </main>
      </>
    );
  }

  const materia = derivarMateria(dados);
  const bloqueado = parecerEhTerminal(dados.estado);

  return (
    <>
      <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
      <main className="envelope">
        <div className="doc-cab">
          <div>
            <span className="eyebrow">Parecer de comissão</span>
            <h1>{materia ? `Parecer a ${materia.numero}` : "Parecer de comissão"}</h1>
            <p className="sub">
              Comissão <b>{dados.comissaoId}</b> · {rotularEstadoParecer(dados.estado)}
            </p>
          </div>
        </div>

        <div className="balcao">
          <FormularioParecer
            valorInicial={{
              relatorio: dados.relatorio ?? "",
              analise: dados.analise ?? "",
              votoRelator: dados.votoRelator ?? "",
            }}
            aoSalvarRascunho={aoSalvarRascunho}
            aoEmitir={aoEmitir}
            enviandoRascunho={estadoRascunho === "enviando"}
            enviandoEmissao={estadoEmissao === "enviando"}
            erro={erroRascunho ?? erroEmissao}
            bloqueado={bloqueado}
            mensagemStatus={mensagemStatus}
          />
          <RailParecer parecer={dados} token={token} />
        </div>
      </main>
    </>
  );
}
