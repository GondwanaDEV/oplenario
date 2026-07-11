"use client";

// PaginaExpediente — a aba "Gerar documento" (Onda B Slice 6). Mirror do tratamento geral de loading/erro de
// parecer/[id]/page.tsx, mas SEM `use(params)` (esta página não edita um registro pré-existente por id de
// rota — ela COMPÕE um documento novo e depois o edita no mesmo lugar, ver formulario-preenchimento.tsx).
//
// `documento` = `documentoHook ?? documentoLocal`: a GERAÇÃO/EDIÇÃO/PROTOCOLAÇÃO já devolvem o DocumentoOut
// completo (síncrono, sem round-trip extra) — `documentoLocal` guarda essa resposta direto. `documentoHook`
// (useDocumentoDetalhe, o GET "pra ter certeza" — mesmo racional de recarregar() em parecer/[id]/page.tsx)
// confirma/atualiza depois; preferido quando presente. Sem esse fallback, o instante em que `documentoId`
// passa de nulo pro id real dispararia um re-render com o hook em "carregando" (reset de id, mesmo padrão
// de use-parecer-editor.ts) e a tela piscaria de volta pra fase de composição por uma fração de segundo.

import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { useDocumentoModelos } from "@/lib/use-documento-modelos";
import { useProtocoloLivro } from "@/lib/use-protocolo-livro";
import { useDocumentoDetalhe } from "@/lib/use-documento-detalhe";
import { useGerarDocumento } from "@/lib/use-gerar-documento";
import { useEditarDocumento } from "@/lib/use-editar-documento";
import { useProtocolarDocumento } from "@/lib/use-protocolar-documento";
import { textoCarimbo } from "@/lib/expediente-vista";
import { TopoInterno } from "../topo";
import { AbasExpediente } from "./abas-expediente";
import { SeletorModelo } from "./seletor-modelo";
import { FormularioPreenchimento, type ValoresEdicao, type ValoresGeracao } from "./formulario-preenchimento";
import { BlocoMerge } from "./bloco-merge";
import { PreviewDocumento } from "./preview-documento";
import { TabelaProtocolo } from "./tabela-protocolo";
import type { DocumentoOut } from "@/lib/contrato-legislativo.gen";
import "./expediente.css";

type UltimaAcao = "gerar" | "rascunho" | "protocolo" | null;

export default function PaginaExpediente() {
  const { token } = useAuth();
  const { dados: modelos, estado: estadoModelos } = useDocumentoModelos(token);
  const { dados: livro, estado: estadoLivro } = useProtocoloLivro(token);

  const [modeloSelecionadoId, setModeloSelecionadoId] = useState<string | null>(null);
  const [documentoLocal, setDocumentoLocal] = useState<DocumentoOut | null>(null);
  const [dadosGerados, setDadosGerados] = useState<Record<string, string>>({});
  const [mensagemStatus, setMensagemStatus] = useState<string | null>(null);
  const [ultimaAcao, setUltimaAcao] = useState<UltimaAcao>(null);

  const { dados: documentoHook, recarregar } = useDocumentoDetalhe(token, documentoLocal?.id ?? null);
  const documento = documentoHook ?? documentoLocal;

  const { gerar, estado: estadoGeracao, erro: erroGeracao } = useGerarDocumento(token);
  const { editar, estado: estadoEdicao, erro: erroEdicao } = useEditarDocumento(token, documento?.id ?? null);
  const { protocolar, estado: estadoProtocolo, erro: erroProtocolo } = useProtocolarDocumento(
    token,
    documento?.id ?? null,
  );

  // Mesma disciplina de parecer/[id]/page.tsx: só o erro da AÇÃO MAIS RECENTE fica visível — sem
  // `ultimaAcao`, um erro de uma mutação já superada continuaria aparecendo depois de outra ter sucesso.
  const erro =
    ultimaAcao === "gerar" ? erroGeracao : ultimaAcao === "rascunho" ? erroEdicao : ultimaAcao === "protocolo" ? erroProtocolo : null;

  async function aoGerar(valores: ValoresGeracao) {
    if (!modeloSelecionadoId) return;
    setUltimaAcao("gerar");
    setMensagemStatus(null);
    try {
      const doc = await gerar({ modeloId: modeloSelecionadoId, assunto: valores.assunto, dados: valores.dados });
      setDocumentoLocal(doc);
      setDadosGerados(valores.dados);
      setMensagemStatus("Documento gerado");
    } catch {
      // erro já refletido pelo hook (erroGeracao).
    }
  }

  async function aoSalvarRascunho(valores: ValoresEdicao) {
    setUltimaAcao("rascunho");
    setMensagemStatus(null);
    if (!documento) return;
    try {
      const doc = await editar({ lockVersion: documento.lockVersion, corpo: valores.corpo, assunto: valores.assunto });
      setDocumentoLocal(doc);
      await recarregar();
      setMensagemStatus("Rascunho salvo");
    } catch {
      // erro já refletido pelo hook (erroEdicao).
    }
  }

  async function aoProtocolar() {
    setUltimaAcao("protocolo");
    setMensagemStatus(null);
    if (!documento) return;
    try {
      const doc = await protocolar({ lockVersion: documento.lockVersion });
      setDocumentoLocal(doc);
      await recarregar();
      setMensagemStatus("Documento protocolado");
    } catch {
      // erro já refletido pelo hook (erroProtocolo).
    }
  }

  return (
    <>
      <TopoInterno area="Expediente" ator={{ nome: "Ana Ribeiro", papel: "Secretária Legislativa" }} />
      <AbasExpediente atual="gerar" />
      <main className="envelope">
        <div className="bancada">
          <div className="controles">
            <section className="bloco" aria-labelledby="modelo-titulo">
              <div className="bloco-cabeca">
                <h2 id="modelo-titulo">Modelo</h2>
                <span className="passo">passo 1 de 3</span>
              </div>
              <div className="bloco-corpo">
                {estadoModelos === "carregando" && <p role="status">Carregando modelos…</p>}
                {estadoModelos === "erro" && (
                  <p role="status">Não foi possível carregar os modelos de documento.</p>
                )}
                {estadoModelos === "pronto" && modelos && (
                  <SeletorModelo
                    modelos={modelos.itens}
                    selecionadoId={modeloSelecionadoId}
                    aoSelecionar={setModeloSelecionadoId}
                    desabilitado={documento !== null}
                  />
                )}
              </div>
            </section>

            <FormularioPreenchimento
              key={documento?.id ?? "novo"}
              documento={documento}
              modeloSelecionadoId={modeloSelecionadoId}
              aoGerar={aoGerar}
              aoSalvarRascunho={aoSalvarRascunho}
              aoProtocolar={aoProtocolar}
              enviandoGeracao={estadoGeracao === "enviando"}
              enviandoRascunho={estadoEdicao === "enviando"}
              enviandoProtocolo={estadoProtocolo === "enviando"}
              erro={erro}
              mensagemStatus={mensagemStatus}
            />

            {documento && <BlocoMerge dados={dadosGerados} carimbo={textoCarimbo(documento)} />}
          </div>

          <PreviewDocumento documento={documento} valoresMerge={Object.values(dadosGerados)} />
        </div>

        {estadoLivro === "carregando" && <p role="status">Carregando o Livro do Protocolo Geral…</p>}
        {estadoLivro === "erro" && <p role="status">Não foi possível carregar o Livro do Protocolo Geral.</p>}
        {estadoLivro === "pronto" && livro && <TabelaProtocolo itens={livro.itens} />}
      </main>
    </>
  );
}
