"use client";

// Aba "Parecer jurídico" da ficha interna (ADR-0019, Eixo 4): os pareceres já ASSINADOS sobre a matéria (o mais novo
// primeiro; os substituídos ficam, marcados) e os pedidos ainda em aberto. Leem a secretaria, os vereadores e o jurídico;
// para os demais papéis o servidor responde 403 e a aba diz isso. A secretaria pede o parecer daqui. O parecer é
// opinativo e não move a matéria; o do portal só aparece depois de a matéria ser deliberada.

import { useState } from "react";
import Link from "next/link";
import { comToken } from "@/lib/nav";
import { AVISO_OPINATIVO, numeroDoParecer, rotuloConclusao, seloDoParecer } from "@/lib/juridico-vista";
import { usePareceresJuridicosDaMateria } from "@/lib/use-pareceres-juridicos-da-materia";
import { formatarData } from "@/lib/formatar-data";
import { ParecerAssinado } from "../juridico/parecer-assinado";
import { NovoPedidoForm } from "../juridico/novo-pedido-form";
import "./juridico-ficha.css";

export function PainelParecerJuridico({ proposicaoId, token = null, papeis = [] }: {
  proposicaoId: string;
  token?: string | null;
  papeis?: string[];
}) {
  const { estado, recarregar } = usePareceresJuridicosDaMateria(token, proposicaoId);
  const [pedindo, setPedindo] = useState(false);
  const [aviso, setAviso] = useState<string | null>(null);
  const ehSecretaria = papeis.includes("secretario");
  const veFila = ehSecretaria || papeis.includes("juridico");

  if (estado.fase === "carregando") return <p role="status">Carregando o parecer jurídico…</p>;
  if (estado.fase === "erro" && estado.status === 403)
    return <p role="status">O parecer jurídico é visível à secretaria, aos vereadores e ao jurídico da Casa.</p>;
  if (estado.fase === "erro") return <p role="alert" className="jf-erro">{estado.mensagem}</p>;

  const { pareceres, pedidosAbertos } = estado.dado;
  const vazio = pareceres.length === 0 && pedidosAbertos.length === 0;

  return (
    <div className="jf-juridico">
      <p className="jf-opinativo" role="note">{AVISO_OPINATIVO}</p>

      {ehSecretaria && !pedindo && (
        <div className="jf-acoes">
          <button type="button" className="btn btn-contorno" onClick={() => { setAviso(null); setPedindo(true); }}>
            Pedir parecer jurídico
          </button>
        </div>
      )}
      {aviso && <p role="status" className="jf-ok">{aviso}</p>}
      {ehSecretaria && pedindo && (
        <NovoPedidoForm
          token={token}
          proposicaoId={proposicaoId}
          onCancelar={() => setPedindo(false)}
          onCriado={(p) => {
            setPedindo(false);
            setAviso(`Pedido aberto: ${p.assunto}.`);
            recarregar();
          }}
        />
      )}

      {vazio && <p>Nenhum parecer jurídico assinado nem pedido em aberto para esta matéria.</p>}

      {pedidosAbertos.length > 0 && (
        <section aria-label="Pedidos em aberto">
          <h4 className="jf-sub">Pedidos em aberto</h4>
          <ul className="jf-itens">
            {pedidosAbertos.map((p) => (
              <li key={p.id}>
                <b>{p.assunto}</b>
                <span>
                  Pedido em {formatarData(p.criadoEm)}
                  {p.prazo ? ` · prazo ${formatarData(p.prazo)}` : ""}
                </span>
                {veFila && <Link href={comToken(`/juridico/${p.id}`, token)}>Abrir na fila do jurídico</Link>}
              </li>
            ))}
          </ul>
        </section>
      )}

      {pareceres.length > 0 && (
        <section aria-label="Pareceres assinados">
          <h4 className="jf-sub">Pareceres assinados</h4>
          <ul className="jf-itens">
            {pareceres.map((p) => {
              const selo = seloDoParecer(p);
              return (
                <li key={p.id}>
                  <details className="jf-detalhe">
                    <summary>
                      <b>{numeroDoParecer(p) ?? "Parecer jurídico"}</b>
                      <span className={`jf-selo jf-selo-${selo.tom}`}>{selo.texto}</span>
                      <span>{rotuloConclusao(p.conclusao)}</span>
                    </summary>
                    <ParecerAssinado parecer={p} compacto />
                  </details>
                  {veFila && <Link href={comToken(`/juridico/${p.pedidoId}`, token)}>Abrir o pedido</Link>}
                </li>
              );
            })}
          </ul>
        </section>
      )}
    </div>
  );
}

