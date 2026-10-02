"use client";

// Rota /comunicados/enviados (interno) — o que a pessoa enviou (ADR-0020), com "lidos x de y", os cientes e os pendentes
// vencidos em destaque (fatia 3). A secretaria e a administração alternam para "Da Casa" (`?escopo=casa`): a Casa
// responde pelos comunicados, não só quem clicou em enviar (Eixo 4). A alternância só aparece para esses papéis — o
// backend recusa o escopo a quem não os tem de qualquer forma.

import { useState } from "react";
import Link from "next/link";
import { useAuth, usePapeis } from "@/lib/auth";
import { comToken } from "@/lib/nav";
import { useEnviados } from "@/lib/use-comunicados";
import { instante, linhaDoEnviado } from "@/lib/comunicacao-vista";
import { TopoInterno } from "../../topo";
import "../../../caixa-da-casa.css";
import "../../../comunicacao.css";

export default function PaginaEnviados() {
  const { token } = useAuth();
  const { papeis } = usePapeis();
  const daCasa = papeis.includes("secretario") || papeis.includes("admin_ente");
  const [escopo, setEscopo] = useState<"meus" | "casa">("meus");
  const { estado, recarregar } = useEnviados(token, daCasa ? escopo : "meus");

  return (
    <>
      <TopoInterno area="Caixa" />
      <main className="envelope com-pagina">
        <header className="com-pagina-cab com-pagina-cab-acoes">
          <div>
            <p className="eyebrow">Comunicados</p>
            <h1>Enviados</h1>
            <p className="com-dica">Quantos receberam, leram e deram ciência em cada comunicado. Abra um para ver pessoa por pessoa.</p>
          </div>
          <div className="com-acoes">
            <Link className="btn btn-primaria btn-mini" href={comToken("/comunicados/novo", token)} prefetch={false}>
              Escrever comunicado
            </Link>
            <Link className="btn btn-contorno btn-mini" href={comToken("/caixa", token)} prefetch={false}>
              Caixa
            </Link>
          </div>
        </header>

        {daCasa && (
          <div className="cx-segs" role="group" aria-label="De quem">
            <button type="button" aria-pressed={escopo === "meus"} onClick={() => setEscopo("meus")}>
              Meus
            </button>
            <button type="button" aria-pressed={escopo === "casa"} onClick={() => setEscopo("casa")}>
              Da Casa
            </button>
          </div>
        )}

        {estado.fase === "carregando" && <p role="status">Carregando os enviados…</p>}
        {estado.fase === "erro" && (
          <div className="com-estado" role="alert">
            <p>{estado.mensagem}</p>
            <button type="button" className="btn btn-contorno btn-mini" onClick={recarregar}>
              Tentar de novo
            </button>
          </div>
        )}
        {estado.fase === "pronto" && estado.dado.itens.length === 0 && (
          <p className="com-vazio">
            {escopo === "casa" && daCasa
              ? "Nenhum comunicado enviado na Casa ainda."
              : "Você ainda não enviou comunicados. Escreva o primeiro: ele vai para a caixa de quem você escolher."}
          </p>
        )}
        {estado.fase === "pronto" && estado.dado.itens.length > 0 && (
          <ul className="com-enviados" aria-label="Comunicados enviados">
            {estado.dado.itens.map((i) => {
              const l = linhaDoEnviado(i);
              return (
                <li key={i.id} className={i.pendentesVencidos > 0 ? "com-enviado-item com-enviado-vencido" : "com-enviado-item"}>
                  <div className="com-enviado-corpo">
                    <p className="com-ref">
                      <span className="com-protocolo">{i.protocolo}</span>
                      <time className="com-quando" dateTime={i.enviadoEm}>
                        {instante(i.enviadoEm)}
                      </time>
                    </p>
                    <h2>
                      <Link href={comToken(`/comunicados/${encodeURIComponent(i.id)}?de=enviados`, token)} prefetch={false}>
                        {i.assunto}
                      </Link>
                    </h2>
                    {escopo === "casa" && daCasa && <p className="com-dica">Enviado por {i.remetenteNome}</p>}
                  </div>
                  <div className="com-enviado-numeros">
                    <span className="chip chip-neutro">{l.leram}</span>
                    {l.cientes && <span className="chip chip-info">{l.cientes}</span>}
                    {l.vencidos && <span className="chip chip-risco">{l.vencidos}</span>}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </main>
    </>
  );
}
