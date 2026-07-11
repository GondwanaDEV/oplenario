// PipelinePosAprovacao — o pipeline-stepper de 5 etapas (Onda B Slice 7). Porte de
// pos-aprovacao.html:131-159 (.pipe/.pipe-trilha/.et). Componente NOVO (spec §4) — não é o AzulejoFaixa já
// existente de tramitação (aquele é a faixa de estágios REGIMENTAIS até a aprovação; este é o pipeline
// FIXO pós-aprovação até virar lei, vocabulário fechado em código, não template-driven por câmara).
//
// Passos 4/5 (Promulgação/Publicação) chegam SEMPRE como "futura" — derivarPipeline (pos-aprovacao-vista)
// nunca produz "atual"/"feita" pra eles nesta fatia (spec §1, sem dado vivo).

import type { EtapaPipeline } from "@/lib/pos-aprovacao-vista";

export function PipelinePosAprovacao({ etapas }: { etapas: EtapaPipeline[] }) {
  return (
    <div className="pipe">
      <div className="pipe-trilha" role="list" aria-label="Etapas da sanção">
        {etapas.map((etapa, i) => (
          <div
            key={etapa.rotulo}
            className={`et ${etapa.situacao}`}
            role="listitem"
            aria-current={etapa.situacao === "atual" ? "step" : undefined}
          >
            <div className="az" aria-hidden="true" />
            <span className="mk" aria-hidden="true">
              {etapa.situacao === "feita" ? "✓" : i + 1}
            </span>
            <b>{etapa.rotulo}</b>
            <span>{etapa.detalhe}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
