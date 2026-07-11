// CardAutografo — card "Autógrafo" (Onda B Slice 7). Porte de pos-aprovacao.html:163-172 (.card .dl), MAS
// sem o link "Abrir o autógrafo (PDF)" do mockup — não existe resolução autógrafo->documento/PDF nesta
// fatia (mesma disciplina de AcoesCard/DadosMateriaCard: campo sem contraparte real no modelo fica FORA,
// Global Constraint "sem dado falso"). `destinatarioTexto` substitui a linha estática "Recebido" do
// mockup (dado real disponível; "recebido" em si não tem timestamp próprio no domínio — só enviadoEm).

import { formatarData } from "@/lib/formatar-data";
import { formatarNumeroAutografo } from "@/lib/pos-aprovacao-vista";
import type { AutografoOut } from "@/lib/contrato-legislativo.gen";

export function CardAutografo({ autografo }: { autografo: AutografoOut }) {
  return (
    <div className="card">
      <h2>Autógrafo</h2>
      <dl className="dl">
        <dt>Autógrafo nº</dt>
        <dd className="mono">{formatarNumeroAutografo(autografo.numero, autografo.ano)}</dd>
        <dt>Enviado ao Executivo</dt>
        <dd className="mono">{formatarData(autografo.enviadoEm)}</dd>
        <dt>Destinatário</dt>
        <dd>{autografo.destinatarioTexto}</dd>
        <dt>Prazo de resposta</dt>
        <dd className="mono">{autografo.prazoRespostaEm ? formatarData(autografo.prazoRespostaEm) : "não informado"}</dd>
      </dl>
      {!autografo.prazoRespostaEm && (
        <p className="nota-gap">
          <span className="tag">GAP</span> O prazo de sanção/veto é o da Lei Orgânica do Município — não foi
          informado ao gerar este autógrafo.
        </p>
      )}
    </div>
  );
}
