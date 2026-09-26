"use client";

// A proposta de requerimento COLETIVO (fatia 2c — autoria-apoiamento.html). Uma página, dois lados:
//   - o AUTOR acompanha quem confirmou e protocola quando quiser ("Revisar e protocolar" → folha de 2 toques;
//     quem ainda não respondeu é dito pelo nome: NÃO constará);
//   - o COAUTOR convidado lê o texto EXATO e confirma com a própria assinatura (mesma folha de 2 toques) ou
//     recusa.
// O texto na ilha-papel é o congelado na proposta: é ele que os coautores assinam e que vai ao protocolo.
// Reusa o CSS do ritual de assinatura (assinar.css), como o "Novo requerimento".

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { usePropostaRequerimento } from "@/lib/use-subscricao";
import { pendentesAoProtocolar, resumoSubscricoes, rotuloSubscricao, iniciais } from "@/lib/subscricao-vista";
import { seloDaAssinatura } from "@/lib/requerimento-vista";
import { comToken } from "@/lib/nav";
import type { RequerimentoColetivoProtocoladoOut } from "@/lib/contrato-legislativo.gen";
import "../../../parecer/[id]/assinar/assinar.css";
import "../../novo/requerimento.css";
import "../../subscricao.css";

type Folha = null | "confirmar" | "protocolar";

export default function PaginaPropostaRequerimento() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { token } = useAuth();
  const { proposta, estado, enviando, responder, protocolar } = usePropostaRequerimento(token, id);
  const [folha, setFolha] = useState<Folha>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [recibo, setRecibo] = useState<RequerimentoColetivoProtocoladoOut | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  if (estado === "carregando") {
    return (
      <main className="tela-estado">
        <h1>Carregando…</h1>
      </main>
    );
  }
  if (estado === "erro" || !proposta) {
    return (
      <main className="tela-estado">
        <h1>Requerimento não encontrado</h1>
        <p>Ele pode ter sido enviado a outros colegas, ou o endereço está incompleto.</p>
      </main>
    );
  }

  if (recibo) {
    const selo = seloDaAssinatura(recibo.assinaturaAlgoritmo);
    return (
      <div className="assinar-pagina req-feito" role="status">
        <h1 className="assinar-titulo">
          Requerimento nº {recibo.sequencial}/{recibo.ano} protocolado
        </h1>
        <p className="req-feito-sub">
          {recibo.coautores.length === 0
            ? "Nenhum coautor tinha confirmado: ele entrou só com a sua assinatura."
            : `Com ${recibo.coautores.length === 1 ? "o coautor" : "os coautores"} ${recibo.coautores.join(", ")}.`}
        </p>
        <p className={`req-selo${selo.provisorio ? " provisorio" : ""}`}>{selo.texto}</p>
        <Link className="btn btn-primaria req-voltar-inicio" href={comToken("/vereador", token)}>
          Voltar ao início
        </Link>
      </div>
    );
  }

  const aberta = proposta.estado === "aguardando_subscricoes";
  const pendentes = pendentesAoProtocolar(proposta.subscricoes);
  const meuConvitePendente = !proposta.souAutor && proposta.minhaSubscricao === "pendente" && aberta;

  async function confirmarFolha() {
    setErro(null);
    if (folha === "protocolar") {
      const r = await protocolar();
      if (r.ok) {
        setFolha(null);
        setRecibo(r.dados);
      } else setErro(r.erro);
      return;
    }
    const r = await responder("confirmar");
    if (r.ok) {
      setFolha(null);
      setAviso("Subscrição confirmada. Sua assinatura já consta neste requerimento.");
    } else setErro(r.erro);
  }

  async function recusar() {
    setErro(null);
    const r = await responder("recusar");
    if (r.ok) setAviso("Você recusou o convite. O autor verá a sua resposta.");
    else setErro(r.erro);
  }

  return (
    <div className="assinar-pagina prop-pagina">
      <button type="button" className="btn btn-fantasma btn-mini assinar-voltar" onClick={() => router.back()}>
        ← Voltar
      </button>
      <h1 className="assinar-titulo">{proposta.souAutor ? "Seu requerimento coletivo" : "Pedido de subscrição"}</h1>
      <p className="prop-resumo">
        {proposta.souAutor ? "Você é o autor." : `De ${proposta.autorNome}.`} {resumoSubscricoes(proposta.subscricoes)}.
      </p>

      <section className="papel" aria-label={proposta.souAutor ? "Texto do requerimento" : "Documento a subscrever"}>
        <div className="cab">
          <h2>{proposta.tipoRequerimento}</h2>
        </div>
        <div className="corpo">
          <p className="req-texto">{proposta.texto}</p>
        </div>
      </section>

      <section className="prop-secao" aria-label="Autoria">
        <h2>Autoria</h2>
        <ul className="sub-lista">
          <li className="sub-linha">
            <span className="coa-av" aria-hidden="true">
              {iniciais(proposta.autorNome)}
            </span>
            <span className="coa-nm">
              <b>{proposta.autorNome}</b>
            </span>
            <span className="sub-chip conf">Autor</span>
          </li>
          {proposta.subscricoes.map((s, i) => {
            const r = rotuloSubscricao(s.estado);
            return (
              <li key={`${s.vereadorNome}-${i}`} className="sub-linha">
                <span className="coa-av" aria-hidden="true">
                  {iniciais(s.vereadorNome)}
                </span>
                <span className="coa-nm">
                  <b>{s.vereadorNome}</b>
                </span>
                <span className={`sub-chip ${r.tom}`}>{r.texto}</span>
              </li>
            );
          })}
        </ul>
      </section>

      {aviso && (
        <p role="status" className="prop-feito">
          {aviso}
        </p>
      )}
      {!aberta && <p className="prop-feito">Este requerimento já foi protocolado. A lista de coautores fechou.</p>}
      {erro && folha === null && (
        <p role="alert" className="erro-inline">
          {erro}
        </p>
      )}

      {aberta && (proposta.souAutor || meuConvitePendente) && (
        <div className="assinar-bar">
          <div className="assinar-bar-in prop-acoes">
            {proposta.souAutor ? (
              <button className="btn btn-primaria" type="button" onClick={() => setFolha("protocolar")}>
                Revisar e protocolar
              </button>
            ) : (
              <>
                <button className="btn btn-primaria" type="button" onClick={() => setFolha("confirmar")}>
                  Revisar e assinar a subscrição
                </button>
                <button className="btn btn-fantasma" type="button" onClick={recusar} disabled={enviando}>
                  Recusar convite
                </button>
              </>
            )}
          </div>
        </div>
      )}

      {folha && (
        <div className="scrim" role="dialog" aria-modal="true" aria-labelledby="sh-tit">
          <div className="sheet">
            <h3 id="sh-tit">{folha === "protocolar" ? "Assinar e protocolar" : "Confirmar subscrição"}</h3>
            <p className="sub">
              {folha === "protocolar"
                ? "O requerimento recebe número oficial e entra na tramitação. Depois de protocolado, o texto não muda."
                : `Você assina o texto acima como coautor do requerimento de ${proposta.autorNome}.`}
            </p>
            {folha === "protocolar" && pendentes.length > 0 && (
              <p className="prop-aviso">
                {pendentes.length === 1 ? `${pendentes[0]} ainda não respondeu e não constará.` : `${pendentes.join(", ")} ainda não responderam e não constarão.`}
              </p>
            )}
            {erro && (
              <p role="status" className="erro-inline">
                {erro}
              </p>
            )}
            <div className="acoes">
              <button className="btn btn-primaria" type="button" onClick={confirmarFolha} disabled={enviando}>
                {enviando ? "Assinando…" : folha === "protocolar" ? "Confirmar e protocolar" : "Confirmar e assinar"}
              </button>
              <button
                className="btn btn-fantasma"
                type="button"
                onClick={() => {
                  setFolha(null);
                  setErro(null);
                }}
              >
                Cancelar
              </button>
            </div>
            <p className="legal">A assinatura é registrada no sistema junto com o texto, e não pode ser desfeita.</p>
          </div>
        </div>
      )}
    </div>
  );
}
