"use client";

// Onda B Slice 2 — editar uma proposicao existente (metadados e/ou texto; "Salvar alterações" = editar!
// + promove nova versao 'edicao' se o texto mudou, spec §2). Carrega o detalhe (use-proposicao-detalhe),
// pre-enche o form, e envia so' o que mudou junto com o lock-version corrente (CAS).

import { use } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useProposicaoDetalhe } from "@/lib/use-proposicao-detalhe";
import { useEditarProposicao } from "@/lib/use-editar-proposicao";
import { comToken } from "@/lib/nav";
import { FormularioProposicao, type ValoresFormulario } from "../formulario-proposicao";
import { TopoInterno } from "../../topo";

export default function PaginaEditarProposicao({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { token } = useAuth();
  const { dados, estado: estadoDetalhe } = useProposicaoDetalhe(token, id);
  const { editar, estado: estadoEnvio, erro } = useEditarProposicao(token, id);
  const router = useRouter();

  async function aoSubmeter(valores: ValoresFormulario) {
    if (!dados) return;
    try {
      await editar({ lockVersion: dados.lockVersion, ...valores });
      router.push(comToken("/proposicoes", token));
    } catch {
      // erro ja' refletido pelo hook.
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
          <h1>Não foi possível carregar esta proposição</h1>
        </main>
      </>
    );
  }

  return (
    <>
      <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
      <main className="envelope">
        <div className="pagina-cab">
          <div>
            <span className="eyebrow">Editor de proposição</span>
            <h1>{dados.ementa}</h1>
          </div>
        </div>
        <FormularioProposicao
          valorInicial={{
            tipo: dados.tipo,
            ano: dados.ano,
            ementa: dados.ementa,
            autorTipo: dados.autorTipo ?? undefined,
            autorTexto: dados.autorTexto ?? undefined,
            objetoIndicacao: dados.objetoIndicacao ?? undefined,
            tipoRequerimento: dados.tipoRequerimento ?? undefined,
            categoriaMocao: dados.categoriaMocao ?? undefined,
            texto: dados.texto ?? undefined,
          }}
          aoSubmeter={aoSubmeter}
          enviando={estadoEnvio === "enviando"}
          erro={erro}
          rotuloAcaoPrimaria="Salvar alterações"
          bloquearIdentidade
        />
      </main>
    </>
  );
}
