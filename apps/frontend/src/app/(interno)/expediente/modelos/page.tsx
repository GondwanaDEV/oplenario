"use client";

// PaginaExpedienteModelos — a aba "Modelos" (Onda B Slice 6, fatia de escrita): CRUD de template de
// documento (criar, editar nome/corpo, desativar). Reativar um modelo desativado fica FORA desta fatia
// (deliberado, não esquecido — ver formulario-modelo.tsx/wire.in.documento-modelo: o PATCH já aceita
// `ativo:true`, só a tela não expõe o botão; sem pedido de cliente validado pra essa jornada).
//
// LISTA + OVERRIDES: `useDocumentoModelos` (GET, só ATIVOS — o mesmo hook do seletor da aba "Gerar
// documento") busca UMA VEZ no mount e não tem `recarregar()`. Em vez de duplicar esse hook só pra ganhar
// um refetch, os efeitos de criar/editar/desativar aplicam localmente em `overrides` (mesmo espírito do
// `documentoLocal` de page.tsx — a resposta do POST/PATCH já é a verdade nova, sem round-trip extra):
//   - criar: adiciona a `overrides[novoId]`.
//   - editar (nome mudou): atualiza `overrides[id]`.
//   - desativar: `overrides[id] = null` (marca "removido da lista de ativos").
// A união itensBase+overrides é o que a lista mostra; um F5 real pega o servidor de novo do zero.

import { useMemo, useState } from "react";
import { useAuth } from "@/lib/auth";
import { useDocumentoModelos } from "@/lib/use-documento-modelos";
import { useModeloDocumentoDetalhe } from "@/lib/use-modelo-documento-detalhe";
import { useCriarModeloDocumento } from "@/lib/use-criar-modelo-documento";
import { useAtualizarModeloDocumento } from "@/lib/use-atualizar-modelo-documento";
import { rotularTipoDocumento } from "@/lib/expediente-vista";
import { TopoInterno } from "../../topo";
import { AbasExpediente } from "../abas-expediente";
import { GuardSecretaria } from "../../guard-secretaria";
import { FormularioModelo, type ValoresEditarModelo, type ValoresNovoModelo } from "./formulario-modelo";
import type { DocumentoModeloOut } from "@/lib/contrato-legislativo.gen";
import "../expediente.css";
import "./modelos.css";

type Visao = { tipo: "lista" } | { tipo: "novo" } | { tipo: "editar"; id: string };

function ConteudoPaginaExpedienteModelos() {
  const { token } = useAuth();
  const { dados: modelos, estado: estadoModelos } = useDocumentoModelos(token);
  const [overrides, setOverrides] = useState<Record<string, DocumentoModeloOut | null>>({});
  const [visao, setVisao] = useState<Visao>({ tipo: "lista" });

  const idEditando = visao.tipo === "editar" ? visao.id : null;
  const { dados: modeloDetalhe, estado: estadoDetalhe } = useModeloDocumentoDetalhe(token, idEditando);

  const { criar, estado: estadoCriar, erro: erroCriar } = useCriarModeloDocumento(token);
  const { atualizar, estado: estadoAtualizar, erro: erroAtualizar } = useAtualizarModeloDocumento(
    token,
    idEditando,
  );

  const itens = useMemo(() => {
    const base = modelos?.itens ?? [];
    const idsBase = new Set(base.map((m) => m.id));
    const mantidos = base
      .filter((m) => overrides[m.id] !== null)
      .map((m) => overrides[m.id] ?? m);
    const novos = Object.entries(overrides)
      .filter(([id, v]) => v !== null && !idsBase.has(id))
      .map(([, v]) => v as DocumentoModeloOut);
    return [...mantidos, ...novos];
  }, [modelos, overrides]);

  async function aoCriar(valores: ValoresNovoModelo) {
    try {
      const criado = await criar(valores);
      setOverrides((o) => ({
        ...o,
        [criado.id]: { id: criado.id, chave: criado.chave, nome: criado.nome, tipoDocumento: criado.tipoDocumento },
      }));
      setVisao({ tipo: "lista" });
    } catch {
      // erro já refletido pelo hook (erroCriar).
    }
  }

  async function aoSalvar(valores: ValoresEditarModelo) {
    if (!idEditando || !modeloDetalhe) return;
    try {
      const salvo = await atualizar({ lockVersion: modeloDetalhe.lockVersion, ...valores });
      setOverrides((o) => ({
        ...o,
        [salvo.id]: { id: salvo.id, chave: salvo.chave, nome: salvo.nome, tipoDocumento: salvo.tipoDocumento },
      }));
      setVisao({ tipo: "lista" });
    } catch {
      // erro já refletido pelo hook (erroAtualizar).
    }
  }

  async function aoDesativar() {
    if (!idEditando || !modeloDetalhe) return;
    try {
      await atualizar({ lockVersion: modeloDetalhe.lockVersion, ativo: false });
      setOverrides((o) => ({ ...o, [idEditando]: null }));
      setVisao({ tipo: "lista" });
    } catch {
      // erro já refletido pelo hook (erroAtualizar).
    }
  }

  return (
    <>
      <TopoInterno area="Expediente" />
      <AbasExpediente atual="modelos" />
      <main className="envelope">
        <div className="modelos-pagina">
          {visao.tipo === "lista" && (
            <section className="bloco" aria-labelledby="modelos-lista-titulo">
              <div className="bloco-cabeca">
                <h2 id="modelos-lista-titulo">Modelos de documento</h2>
                <button type="button" className="btn btn-primaria" onClick={() => setVisao({ tipo: "novo" })}>
                  + Novo modelo
                </button>
              </div>
              <div className="bloco-corpo">
                {estadoModelos === "carregando" && <p role="status">Carregando modelos…</p>}
                {estadoModelos === "erro" && <p role="status">Não foi possível carregar os modelos de documento.</p>}
                {estadoModelos === "pronto" && itens.length === 0 && (
                  <p className="modelos-vazio">Nenhum modelo ativo ainda. Crie o primeiro com &quot;Novo modelo&quot;.</p>
                )}
                {estadoModelos === "pronto" && itens.length > 0 && (
                  <ul className="modelos-lista">
                    {itens.map((m) => (
                      <li key={m.id} className="modelo-linha">
                        <div className="modelo-linha-info">
                          <b>{m.nome}</b>
                          <span className="modelo-linha-meta">
                            {m.chave} · {rotularTipoDocumento(m.tipoDocumento)}
                          </span>
                        </div>
                        <button
                          type="button"
                          className="btn btn-contorno"
                          onClick={() => setVisao({ tipo: "editar", id: m.id })}
                        >
                          Editar
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </section>
          )}

          {visao.tipo === "novo" && (
            <FormularioModelo
              key="novo"
              modo="novo"
              modelo={null}
              aoCriar={aoCriar}
              aoSalvar={() => {}}
              aoDesativar={() => {}}
              aoCancelar={() => setVisao({ tipo: "lista" })}
              enviando={estadoCriar === "enviando"}
              erro={erroCriar}
            />
          )}

          {visao.tipo === "editar" && (
            <>
              {estadoDetalhe === "carregando" && <p role="status">Carregando modelo…</p>}
              {estadoDetalhe === "erro" && <p role="status">Não foi possível carregar este modelo.</p>}
              {estadoDetalhe === "pronto" && modeloDetalhe && (
                <FormularioModelo
                  key={modeloDetalhe.id}
                  modo="editar"
                  modelo={modeloDetalhe}
                  aoCriar={() => {}}
                  aoSalvar={aoSalvar}
                  aoDesativar={aoDesativar}
                  aoCancelar={() => setVisao({ tipo: "lista" })}
                  enviando={estadoAtualizar === "enviando"}
                  erro={erroAtualizar}
                />
              )}
            </>
          )}
        </div>
      </main>
    </>
  );
}

export default function PaginaExpedienteModelos() {
  return (
    <GuardSecretaria>
      <ConteudoPaginaExpedienteModelos />
    </GuardSecretaria>
  );
}
