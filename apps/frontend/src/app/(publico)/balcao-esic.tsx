"use client";

// BalcaoEsic — o balcão de e-SIC/LAI (Task 2.1, Fatia A2.2). Porte de portal-cidadao.html:519-584, com
// UMA mudança estrutural sobre o mockup: a tela-fonte mostra um `.pedido` FIXO de demonstração ("Pedido
// nº 2026/00488") como se fosse dado real. Isso é fabricado — violaria a Global Constraint "sem dado
// falso" portar literalmente. Como esta fatia liga a rota pública REAL de acompanhamento
// (GET /esic/acompanhar/{protocolo}), a UI inverte: o `.pedido`/azulejo/anel só aparecem DEPOIS que o
// cidadão busca um protocolo de verdade — antes disso, um formulário controlado ("Acompanhar pelo
// número") é a superfície, sem inventar um pedido de exemplo.
//
// "Abrir um pedido" (fluxo de escrita) exige identificação (gov.br) — fora do escopo de leitura pública
// desta fatia (Global Constraints "só leitura pública") -> <EmBreve>.
//
// `buscarPublico` colapsa 404 e falha de rede no mesmo `null` (degradação por seção) — não dá para
// distinguir "protocolo não existe" de "erro transitório" com a fundação atual; a mensagem cobre os
// dois casos honestamente, sem afirmar qual dos dois ocorreu.

import { useState, type FormEvent } from "react";
import { buscarPublico } from "@/lib/portal-api";
import { AzulejoFaixa } from "@/lib/charts/azulejo-faixa";
import { descreverFaixa } from "@/lib/tramitacao-vista";
import { AnelPrazo } from "@/lib/charts/anel-prazo";
import { EmBreve } from "@/lib/em-breve";
import { derivarStatusEsic, DIAS_TOTAL_LAI } from "@/lib/esic-vista";
import type { AcompanhamentoEsicOut } from "@/lib/contrato-portal.gen";

type EstadoBusca = "ocioso" | "buscando" | "encontrado" | "nao-encontrado";

export function BalcaoEsic({ ente }: { ente: string }) {
  const [protocolo, setProtocolo] = useState("");
  const [estado, setEstado] = useState<EstadoBusca>("ocioso");
  const [status, setStatus] = useState<AcompanhamentoEsicOut | null>(null);

  async function aoSubmeter(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    const valor = protocolo.trim();
    if (!valor) return;
    setEstado("buscando");
    const resultado = await buscarPublico<AcompanhamentoEsicOut>(ente, "esic", "acompanhar", valor);
    if (!resultado) {
      setStatus(null);
      setEstado("nao-encontrado");
      return;
    }
    setStatus(resultado);
    setEstado("encontrado");
  }

  const vista = status ? derivarStatusEsic(status) : null;

  return (
    <article className="balcao balcao-esic" aria-labelledby="esic-titulo">
      <p className="eyebrow">e-SIC · Lei de Acesso à Informação</p>
      <h3 id="esic-titulo">Acesso à informação</h3>
      <p className="lead">
        Peça <b>qualquer informação pública</b> da Câmara — de uma lei a um contrato, uma diária ou a
        folha de pagamento. É um direito seu, e a resposta tem prazo.
      </p>

      <form className="acompanhar-esic" onSubmit={aoSubmeter}>
        <label htmlFor="esic-protocolo">Acompanhar pelo número do protocolo</label>
        <div className="acompanhar-esic-campo">
          <input
            id="esic-protocolo"
            type="text"
            inputMode="text"
            value={protocolo}
            onChange={(evento) => setProtocolo(evento.target.value)}
            placeholder="Ex.: 2026/00488"
          />
          <button className="btn btn-contorno" type="submit" disabled={estado === "buscando"}>
            {estado === "buscando" ? "Buscando…" : "Acompanhar"}
          </button>
        </div>
      </form>

      {estado === "nao-encontrado" && (
        <EmBreve
          titulo="Pedido não encontrado"
          motivo="Não encontramos nenhum pedido com esse número. Confira o número de protocolo (o mesmo do recibo) ou tente novamente em instantes."
        />
      )}

      {estado === "encontrado" && status && vista && (
        <>
          <div className="pedido">
            <div>
              <span className="num">Pedido nº {status.protocolo}</span>
              <p className="tipo-obj">
                Situação: <b>{vista.rotuloSituacao}</b>
              </p>
            </div>
            {vista.diasRestantes !== null && (
              <AnelPrazo
                diasRestantes={vista.diasRestantes}
                diasTotal={DIAS_TOTAL_LAI}
                rotulo={`Prazo legal do pedido ${status.protocolo}`}
                tamanho={92}
              />
            )}
            <p className="pedido-legenda">
              <span>
                Prazo da Lei de Acesso: <b>20 dias</b>, prorrogável por mais 10.
              </span>
            </p>
          </div>

          <div className="tramitacao">
            <p className="rotulo-faixa">Situação do pedido</p>
            <AzulejoFaixa
              estagios={vista.estagios}
              rotuloAria={descreverFaixa(`pedido ${status.protocolo}`, vista.estagios)}
            />
          </div>
        </>
      )}

      <div className="balcao-acoes">
        <EmBreve
          titulo="Abrir um pedido"
          motivo="Abrir um novo pedido de acesso à informação exige identificação (Entrar com gov.br) — chega numa fatia futura de autenticação. Enquanto isso, você já pode acompanhar um pedido existente pelo número acima."
        />
      </div>

      <p className="recibo-nota">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
          <path d="M9 11l3 3L22 4" />
          <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
        </svg>
        <span>
          Ao enviar, você recebe <b>na hora</b> um número de protocolo e um <b>recibo</b> (ex.:{" "}
          <span className="prot">2026/00489</span>) — a prova de que pediu e o{" "}
          <b>marco em que o prazo começa a contar</b>. O recibo chega também por e-mail.
        </span>
      </p>
      <p className="recurso-nota">
        <svg width="15" height="15" viewBox="0 0 15 15" aria-hidden="true">
          <path d="M9 3L4.5 7.5 9 12" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <span>Não concordou com a resposta? Você pode recorrer — o pedido vai a uma nova instância de revisão.</span>
      </p>
      <p className="ja-publicado">
        <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
          <circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.3" />
          <path d="M8 7.2v3.3M8 5h.01" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
        <span>Antes de pedir, dê uma olhada: muita coisa já está publicada em Transparência e Dados abertos.</span>
      </p>
    </article>
  );
}
