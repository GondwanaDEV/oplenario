// O parecer jurídico ASSINADO, em leitura (ADR-0019): relatório, fundamentação, conclusão e o bloco de assinatura — quem
// assinou, com que OAB e qualificação, e quando. É o mesmo desenho no detalhe do pedido, na ficha da matéria e (sem o
// "substituído") no portal. Imutável: o que se lê aqui é o que foi assinado.

import { blocoDeAssinatura, linhaDeOrigemDoParecer, numeroDoParecer, rotuloConclusao, seloDoParecer } from "@/lib/juridico-vista";
import type { AssinaturaJuridicaOut, ParecerJuridicoOut } from "@/lib/contrato-juridico.gen";
import "./juridico.css";

export function BlocoAssinatura({ assinatura }: { assinatura: AssinaturaJuridicaOut }) {
  const b = blocoDeAssinatura(assinatura);
  return (
    <footer className="jur-assinatura" aria-label="Assinatura">
      <b>{b.nome}</b>
      <span>{b.registro}</span>
      <span>{b.quando}</span>
    </footer>
  );
}

export function ParecerAssinado({ parecer, compacto = false }: { parecer: ParecerJuridicoOut; compacto?: boolean }) {
  const selo = seloDoParecer(parecer);
  const numero = numeroDoParecer(parecer);
  // ADR-0019 fatia 2a: quando o rascunho partiu da nota técnica da IA, a ficha diz — e diz quem revisou e assinou
  const origem = linhaDeOrigemDoParecer(parecer);
  return (
    <article className="jur-papel" aria-label={numero ? `${numero}, texto` : "Parecer jurídico assinado"}>
      {/* compacto (dentro do <details> da ficha): o número e o selo já estão no resumo dele */}
      {!compacto && (
        <header className="jur-papel-cab">
          <p className="jur-papel-numero">{numero ?? "Parecer jurídico"}</p>
          <span className={`jur-selo jur-selo-${selo.tom}`}>{selo.texto}</span>
        </header>
      )}
      {origem && <p className="jur-origem" role="note">{origem}</p>}
      {parecer.substituido && (
        <p className="jur-dica" role="note">Este parecer foi substituído por um mais novo, assinado depois.</p>
      )}
      {compacto ? <h4>Relatório</h4> : <h2>Relatório</h2>}
      <p className="jur-texto">{parecer.relatorio}</p>
      {compacto ? <h4>Fundamentação</h4> : <h2>Fundamentação</h2>}
      <p className="jur-texto">{parecer.fundamentacao}</p>
      {compacto ? <h4>Conclusão</h4> : <h2>Conclusão</h2>}
      <p className="jur-conclusao">{rotuloConclusao(parecer.conclusao)}</p>
      {parecer.assinatura && <BlocoAssinatura assinatura={parecer.assinatura} />}
    </article>
  );
}
