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
import {
  derivarMateria,
  derivarRelatoria,
  rotularEstadoParecer,
  parecerEhTerminal,
} from "@/lib/parecer-vista";
import { rotularComissao } from "@/lib/comissao-vista";
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
  // Cada hook de mutação só limpa o PRÓPRIO `erro` quando ELE inicia um novo envio — não o do outro hook.
  // Sem rastrear qual foi a última ação, `erroRascunho ?? erroEmissao` mostraria um erro de rascunho já
  // superado depois de uma emissão bem-sucedida (ou vice-versa). `ultimaAcao` garante que só o erro da
  // ação mais recente é exibido.
  const [ultimaAcao, setUltimaAcao] = useState<"rascunho" | "emissao" | null>(null);
  const erro = ultimaAcao === "rascunho" ? erroRascunho : ultimaAcao === "emissao" ? erroEmissao : null;

  async function aoSalvarRascunho(valores: ValoresParecer) {
    setUltimaAcao("rascunho");
    setMensagemStatus(null);
    try {
      await salvar({ relatorio: valores.relatorio, analise: valores.analise });
      setMensagemStatus("Rascunho salvo");
    } catch {
      // erro ja' refletido pelo hook (erroRascunho).
    }
  }

  async function aoEmitir(valores: ValoresParecer) {
    setUltimaAcao("emissao");
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
        <TopoInterno area="Proposições" />
        <main className="envelope">
          <p role="status">Carregando…</p>
        </main>
      </>
    );
  }

  if (estadoDetalhe === "erro" || !dados) {
    return (
      <>
        <TopoInterno area="Proposições" />
        <main className="tela-estado">
          <h1>Não foi possível carregar este parecer</h1>
        </main>
      </>
    );
  }

  const materia = derivarMateria(dados);
  // O subtítulo dizia "Comissão <comissaoId>" e imprimia o UUID (defeito #11 do ledger, `MATA`). Passa a
  // usar o MESMO rótulo do rail — uma fonte só de verdade pra comissão nesta tela.
  const relatoria = derivarRelatoria(dados);
  const bloqueado = parecerEhTerminal(dados.estado);

  return (
    <>
      <TopoInterno area="Proposições" />
      <main className="envelope">
        <div className="doc-cab">
          <div>
            <span className="eyebrow">Parecer de comissão</span>
            <h1>{materia ? `Parecer a ${materia.numero}` : "Parecer de comissão"}</h1>
            <p className="sub">
              <b>{rotularComissao(relatoria.comissaoNome)}</b> · {rotularEstadoParecer(dados.estado)}
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
            erro={erro}
            bloqueado={bloqueado}
            mensagemStatus={mensagemStatus}
          />
          <RailParecer parecer={dados} token={token} />
        </div>
      </main>
    </>
  );
}
