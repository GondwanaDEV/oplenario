"use client";

// ADR-0016 — a Câmara no console (porte de console-operador-tenant.html, arquétipo config + lista). O que a
// Operação vê é METADADO: o estado do handoff, o 1º administrador convidado e a atuação da Operação nesta Casa,
// selada. Os dados de dentro da Casa ficam fora — o acesso de suporte (12.7) é outra fatia, e esta tela diz isso
// em vez de mostrar um botão que não faz nada. Os recursos por Casa (12.3) também esperam fatia própria.

import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import {
  type FichaDaCasa,
  reaplicarLogin,
  reenviarConvite,
  rotuloAcao,
  rotuloEstadoCasa,
  seloCurto,
  useFichaDaCasa,
} from "@/lib/use-operacao";
import { dataHora, haQuanto } from "../../quando";
import { AcessoDaCamara } from "./acesso-da-camara";
import { EncerramentoDaCamara } from "./encerramento";

export default function CamaraNoConsole() {
  const { ente } = useParams<{ ente: string }>();
  const params = useSearchParams();
  const { token } = useAuth();
  const { dados, estado, recarregar } = useFichaDaCasa(ente, token);
  const recemProvisionada = params.get("provisionada");

  return (
    <>
      <nav className="op-migalha" aria-label="Trilha">
        <Link href={comToken("/operacao", token)}>Câmaras</Link> <span aria-hidden="true">›</span>
        <span>{dados?.casa.nome ?? "Câmara"}</span>
      </nav>
      {estado === "carregando" && <p className="op-vazio">Carregando a câmara…</p>}
      {estado === "nao-encontrada" && <p className="op-aviso erro" role="alert">Esta câmara não está no registro.</p>}
      {estado === "sem-sessao" && (
        <p className="op-aviso erro" role="alert">
          Sua sessão do console expirou. <a className="op-link" href="/operacao/entrar">Entrar de novo</a>
        </p>
      )}
      {estado === "erro" && <p className="op-aviso erro" role="alert">Não deu para carregar a câmara agora.</p>}
      {dados && (
        <Ficha ficha={dados} token={token} recemProvisionada={recemProvisionada} aoMudar={recarregar} />
      )}
    </>
  );
}

function Ficha({ ficha, token, recemProvisionada, aoMudar }: {
  ficha: FichaDaCasa;
  token: string | null;
  recemProvisionada: string | null;
  aoMudar: () => void;
}) {
  const { casa, primeiroAdmin, atuacao, pedidoAberto, encerramento } = ficha;
  const [ocupado, setOcupado] = useState<"convite" | "login" | null>(null);
  const [aviso, setAviso] = useState<{ tipo: "ok" | "erro"; texto: string } | null>(null);

  async function agir(tipo: "convite" | "login") {
    if (ocupado) return;
    setOcupado(tipo);
    setAviso(null);
    const r = tipo === "convite" ? await reenviarConvite(casa.enteId, token) : await reaplicarLogin(casa.enteId, token);
    setOcupado(null);
    if (!r.ok) return setAviso({ tipo: "erro", texto: r.mensagem });
    setAviso({
      tipo: "ok",
      texto: tipo === "convite"
        ? `Convite reenviado para ${primeiroAdmin?.email ?? "o 1º administrador"}.`
        : "Configuração de login reaplicada nesta câmara.",
    });
    aoMudar();
  }

  const aguardando = casa.estado === "provisionar";
  return (
    <>
      <div className="op-ten-cab">
        <span className="op-uf" aria-hidden="true">{casa.uf}</span>
        <div>
          <h1>{casa.nome}</h1>
          <span className="meta">
            {casa.municipio?.nome ? `${casa.municipio.nome} · ${casa.uf}` : casa.uf}
            {casa.municipio?.ibge ? ` · IBGE ${casa.municipio.ibge}` : ""}
          </span>
        </div>
        <span className={`op-st op-st-${casa.estado}`}><span className="dot" aria-hidden="true" />{rotuloEstadoCasa(casa.estado)}</span>
      </div>

      {recemProvisionada === "enviado" && aguardando && (
        <p className="op-aviso" role="status">
          <b>Câmara provisionada.</b> O convite foi para {primeiroAdmin?.email}. Ela passa às mãos da Casa quando o
          administrador entrar.
        </p>
      )}
      {recemProvisionada === "falhou" && aguardando && !casa.conviteEnviadoEm && (
        <p className="op-aviso erro" role="alert">
          <b>A câmara foi registrada, mas o convite não saiu.</b> O serviço de login não respondeu. Tente reenviar
          abaixo.
        </p>
      )}
      {aviso && <p className={`op-aviso${aviso.tipo === "erro" ? " erro" : ""}`} role={aviso.tipo === "erro" ? "alert" : "status"}>{aviso.texto}</p>}

      <section className="op-secao" aria-labelledby="titulo-handoff">
        <h2 id="titulo-handoff">Handoff</h2>
        <p className="aj">Provisionamento é handoff, não controle: quem administra a Casa é o administrador dela.</p>
        <div className="op-cartao">
          <div className="op-cartao-top">
            <span className="ic" aria-hidden="true">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="9" cy="8" r="4" /><path d="M2 21a7 7 0 0 1 14 0" /><path d="M19 8v6M22 11h-6" /></svg>
            </span>
            <div className="estado">
              <b>{aguardando ? "Aguardando o 1º administrador" : "A Casa assumiu"}</b>
              <span>
                {aguardando
                  ? casa.conviteEnviadoEm ? `Convite enviado ${haQuanto(casa.conviteEnviadoEm)}` : "O convite ainda não saiu"
                  : casa.ativadaEm ? `O administrador entrou ${haQuanto(casa.ativadaEm)}` : "Ativa"}
              </span>
            </div>
            {aguardando && (
              <div className="acao">
                <button className="btn btn-contorno" type="button" onClick={() => agir("convite")} disabled={!!ocupado}
                  aria-busy={ocupado === "convite"}>
                  {ocupado === "convite" ? "Reenviando…" : casa.conviteEnviadoEm ? "Reenviar convite" : "Enviar convite"}
                </button>
              </div>
            )}
          </div>
          <div className="op-cartao-corpo">
            <dl className="op-dl">
              <dt>1º administrador</dt><dd>{primeiroAdmin?.nome ?? "—"}</dd>
              <dt>E-mail do convite</dt><dd>{primeiroAdmin?.email ?? "—"}</dd>
              <dt>Provisionada em</dt><dd>{casa.criadaEm ? dataHora(casa.criadaEm) : "—"}</dd>
            </dl>
          </div>
        </div>
      </section>

      <AcessoDaCamara casa={casa} pedidoAberto={pedidoAberto ?? null} token={token} aoMudar={aoMudar} />
      <EncerramentoDaCamara casa={casa} encerramento={encerramento} pedidoAberto={pedidoAberto ?? null} token={token}
        aoMudar={aoMudar} />

      <section className="op-secao" aria-labelledby="titulo-suporte">
        <h2 id="titulo-suporte">Acesso de suporte</h2>
        <p className="aj">Você não vê os dados desta câmara. A Operação opera metadados; dado de dentro da Casa só com autorização dela.</p>
        <div className="op-cartao">
          <div className="op-cartao-corpo">
            <ul className="op-prin">
              <li><Cadeado /><span><b>Sem acesso aos dados.</b> A lista e esta ficha mostram só o que a Operação precisa para operar: nome, estado e o que ela mesma fez aqui.</span></li>
              <li><Cadeado /><span><b>A câmara autoriza.</b> Acesso a dados para suporte, quando existir, sai de um pedido que o administrador da Casa aprova — com prazo, justificativa e registro.</span></li>
            </ul>
          </div>
        </div>
      </section>

      <section className="op-secao" aria-labelledby="titulo-atuacao">
        <h2 id="titulo-atuacao">Sua atuação nesta câmara</h2>
        <p className="aj">O que a Operação fez neste tenant, do mais recente ao mais antigo. Cada registro sela o anterior.</p>
        <div className="op-cartao op-audit">
          {atuacao.map((a) => (
            <div className="op-ev" key={a.id}>
              <span className="q">{dataHora(a.em)}</span>
              <span className="o">
                <b>{rotuloAcao(a.acao)}</b>
                <span>{a.operador ?? "pela própria câmara"}</span>
              </span>
              <span className="selo" title={`selo ${a.selo}`}>selo {seloCurto(a.selo)}</span>
            </div>
          ))}
          <div className="rod">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7z" /></svg>
            <span><b>Append-only.</b> Nenhum registro é editado nem apagado; mexer num quebra o selo de todos depois dele.</span>
          </div>
        </div>
      </section>

      <section className="op-secao" aria-labelledby="titulo-login">
        <h2 id="titulo-login">Login da câmara</h2>
        <p className="aj">
          Reaplica a configuração de login desta câmara — por exemplo, depois que o “Entrar com gov.br” foi ligado na
          plataforma. Não mexe em contas nem em senhas.
        </p>
        <button className="btn btn-contorno" type="button" onClick={() => agir("login")} disabled={!!ocupado}
          aria-busy={ocupado === "login"}>
          {ocupado === "login" ? "Reaplicando…" : "Reaplicar configuração de login"}
        </button>
      </section>
      <div style={{ height: "2.5rem" }} />
    </>
  );
}

function Cadeado() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  );
}
