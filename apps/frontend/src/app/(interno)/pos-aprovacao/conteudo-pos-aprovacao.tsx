"use client";

// ConteudoPosAprovacao — o corpo real da rota /pos-aprovacao/:id (Onda B Slice 7), separado do resolvedor
// de `params` (ver [id]/page.tsx) pelo MESMO motivo já documentado em ficha-materia/conteudo-ficha-
// materia.tsx: manter a lógica testável sem depender de `use()`+Suspense num render() direto de teste.
//
// Compõe DOIS GETs em paralelo (mesmo idioma de expediente/page.tsx compondo modelos+livro): useProposicao
// Detalhe (cabeçalho: número/ementa — PosAprovacaoOut NÃO carrega a proposição, spec §3.1 é só autógrafo+
// tramitação) + usePosAprovacao (autógrafo+tramitação executiva).
//
// `posAprovacaoLocal ?? posAprovacaoHook` — ORDEM INVERTIDA de `documentoHook ?? documentoLocal` do
// expediente: lá o hook troca de `id` (null -> id real) após a mutação, então o hook "vence" assim que
// resolve. Aqui `proposicaoId` é FIXO desde o primeiro render (vem da rota, carregado uma vez) — o hook
// nunca reconsulta sozinho depois de "Gerar autógrafo"/"Registrar retorno". `posAprovacaoLocal` guarda a
// resposta da mutação mais recente (sempre mais fresca que o GET inicial) e por isso tem precedência.
import { useState } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { useProposicaoDetalhe } from "@/lib/use-proposicao-detalhe";
import { usePosAprovacao } from "@/lib/use-pos-aprovacao";
import { useGerarAutografo } from "@/lib/use-gerar-autografo";
import { useRegistrarResposta } from "@/lib/use-registrar-resposta";
import { formatarNumeroProposicao } from "@/lib/proposicoes-vista";
import { derivarPipeline } from "@/lib/pos-aprovacao-vista";
import { comToken } from "@/lib/nav";
import { TopoInterno } from "../topo";
import { PipelinePosAprovacao } from "./pipeline-pos-aprovacao";
import { CardAutografo } from "./card-autografo";
import { CardPrazoExecutivo } from "./card-prazo-executivo";
import { FormRegistrarRetorno, type ValoresRetorno } from "./form-registrar-retorno";
import type { PosAprovacaoOut } from "@/lib/contrato-legislativo.gen";
import "./pos-aprovacao.css";

type UltimaAcao = "gerar" | "registrar" | null;

export function ConteudoPosAprovacao({ id }: { id: string }) {
  const { token } = useAuth();
  const { dados: proposicao, estado: estadoProposicao } = useProposicaoDetalhe(token, id);
  const { dados: posAprovacaoHook, estado: estadoPosAprovacao } = usePosAprovacao(token, id);

  const [posAprovacaoLocal, setPosAprovacaoLocal] = useState<PosAprovacaoOut | null>(null);
  const [mostrarForm, setMostrarForm] = useState(false);
  const [mensagemStatus, setMensagemStatus] = useState<string | null>(null);
  const [ultimaAcao, setUltimaAcao] = useState<UltimaAcao>(null);

  const posAprovacao = posAprovacaoLocal ?? posAprovacaoHook;
  const autografo = posAprovacao?.autografo ?? null;
  const tramitacaoExecutiva = posAprovacao?.tramitacaoExecutiva ?? null;

  const { gerar, estado: estadoGeracao, erro: erroGeracao } = useGerarAutografo(token, id);
  const { registrar, estado: estadoRegistro, erro: erroRegistro } = useRegistrarResposta(
    token,
    autografo?.id ?? null,
  );

  // Só a AÇÃO MAIS RECENTE mostra erro (mesma disciplina de expediente/page.tsx).
  const erro = ultimaAcao === "gerar" ? erroGeracao : ultimaAcao === "registrar" ? erroRegistro : null;

  async function aoGerarAutografo() {
    setUltimaAcao("gerar");
    setMensagemStatus(null);
    try {
      const resultado = await gerar({});
      setPosAprovacaoLocal(resultado);
      setMensagemStatus("Autógrafo gerado e enviado ao Executivo");
    } catch {
      // erro já refletido pelo hook (erroGeracao).
    }
  }

  async function aoRegistrarRetorno(valores: ValoresRetorno) {
    if (!tramitacaoExecutiva) return;
    setUltimaAcao("registrar");
    setMensagemStatus(null);
    try {
      const atualizada = await registrar({
        lockVersion: tramitacaoExecutiva.lockVersion,
        resultado: valores.resultado,
        vetoTipo: valores.vetoTipo,
        vetoRazoes: valores.vetoRazoes,
      });
      setPosAprovacaoLocal({ autografo, tramitacaoExecutiva: atualizada });
      setMensagemStatus("Retorno do Executivo registrado");
      setMostrarForm(false);
    } catch {
      // erro já refletido pelo hook (erroRegistro).
    }
  }

  if (estadoProposicao === "carregando" || estadoPosAprovacao === "carregando") {
    return (
      <>
        <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
        <main className="envelope">
          <p role="status">Carregando…</p>
        </main>
      </>
    );
  }

  if (estadoProposicao === "erro" || !proposicao) {
    return (
      <>
        <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
        <main className="tela-estado">
          <h1>Não foi possível carregar esta matéria</h1>
        </main>
      </>
    );
  }

  const numero = formatarNumeroProposicao(proposicao.tipo, proposicao.sequencial, proposicao.ano);

  return (
    <>
      <TopoInterno area="Proposições" ator={{ nome: "Rita Campos", papel: "Servidora legislativa" }} />
      <main className="envelope">
        <nav className="trilha" aria-label="Trilha de navegação">
          <Link href={comToken("/proposicoes", token)}>Proposições</Link>
          <span className="sep" aria-hidden="true">/</span>
          <Link href={comToken(`/ficha-materia/${id}`, token)}>{numero}</Link>
          <span className="sep" aria-hidden="true">/</span>
          <b>Pós-aprovação</b>
        </nav>

        <div className="pg-cab">
          <h1>Da aprovação à lei</h1>
          <p className="sub">
            {numero} — {proposicao.ementa}
          </p>
        </div>

        {estadoPosAprovacao === "erro" && (
          <p role="status">Não foi possível carregar o pós-aprovação desta matéria.</p>
        )}

        {/* Mensagem de status/erro PERSISTENTE através da troca "sem autógrafo" -> "com autógrafo": ao
            gerar com sucesso, a página muda de branch (o card "Nenhum autógrafo" some, o pipeline entra)
            no MESMO render — sem esta região compartilhada, a confirmação de "Gerar autógrafo" nunca
            apareceria (ficaria presa dentro de um card que já deixou de existir). */}
        {mensagemStatus && ultimaAcao === "gerar" && <p role="status">{mensagemStatus}</p>}
        {erro && ultimaAcao === "gerar" && !autografo && (
          <p role="alert" className="form-erro">
            {erro}
          </p>
        )}

        {estadoPosAprovacao === "pronto" && !autografo && (
          <div className="card">
            <h2>Autógrafo</h2>
            <p>Nenhum autógrafo foi gerado ainda para esta matéria.</p>
            <div className="acoes">
              <button
                type="button"
                className="btn btn-primaria"
                disabled={estadoGeracao === "enviando"}
                onClick={aoGerarAutografo}
              >
                Gerar autógrafo e enviar ao Executivo
              </button>
            </div>
          </div>
        )}

        {autografo && (
          <>
            <PipelinePosAprovacao etapas={derivarPipeline(autografo, tramitacaoExecutiva)} />

            <div className="grade">
              <div>
                <CardAutografo autografo={autografo} />
              </div>

              <aside>
                {tramitacaoExecutiva?.estado === "aguardando" && (
                  <>
                    <CardPrazoExecutivo autografo={autografo} />
                    {mensagemStatus && ultimaAcao === "registrar" && <p role="status">{mensagemStatus}</p>}
                    {!mostrarForm && (
                      <div className="acoes">
                        <button
                          type="button"
                          className="btn btn-contorno btn-mini"
                          onClick={() => setMostrarForm(true)}
                        >
                          Registrar retorno
                        </button>
                      </div>
                    )}
                    {mostrarForm && (
                      <FormRegistrarRetorno
                        aoRegistrar={aoRegistrarRetorno}
                        aoCancelar={() => setMostrarForm(false)}
                        enviando={estadoRegistro === "enviando"}
                        erro={ultimaAcao === "registrar" ? erroRegistro : null}
                      />
                    )}
                  </>
                )}

                {tramitacaoExecutiva && tramitacaoExecutiva.estado !== "aguardando" && (
                  <div className="card">
                    <h2>Desfecho</h2>
                    <p>
                      {tramitacaoExecutiva.estado === "vetado" &&
                        "Veto aguardando apreciação da Câmara (a votação de apreciação segue outro canal — o placar do plenário)."}
                      {tramitacaoExecutiva.estado === "veto_mantido" && "Veto mantido pela Câmara — a matéria é arquivada."}
                      {tramitacaoExecutiva.estado === "veto_derrubado" &&
                        "Veto derrubado pela Câmara — a lei segue para promulgação."}
                      {(tramitacaoExecutiva.estado === "sancionado" ||
                        tramitacaoExecutiva.estado === "sancao_tacita") &&
                        "A matéria foi sancionada e segue para promulgação/publicação."}
                    </p>
                  </div>
                )}
              </aside>
            </div>
          </>
        )}
      </main>
    </>
  );
}
