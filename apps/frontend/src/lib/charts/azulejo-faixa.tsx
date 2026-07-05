// AzulejoFaixa — a faixa de azulejo da tramitação (Task 0.5, Fatia A2.0). Porte PARAMETRIZADO do SVG de
// portal-cidadao.html:432-453 (5 tiles fixos, cores hardcoded por posição) — aqui generalizado para N
// estágios, com o grafismo dirigido pela SITUAÇÃO de cada um (concluído/ativo/pendente), não pela
// posição: concluído = marca sólida; ativo = marca menor com acento; pendente = contorno tracejado. As
// cores vêm de ../../sistema/tokens.css via classes (.azulejo-tile--*), mesma disciplina do AnelPrazo
// (zero lib de chart, skill dataviz: informa, nunca decora).

import type { EstagioTramitacao } from "../tramitacao-vista";

const LARGURA_TILE = 120;
const TAMANHO_ICONE = 104;
const ALTURA = 132;

export function AzulejoFaixa({
  estagios,
  rotuloAria,
}: {
  estagios: EstagioTramitacao[];
  rotuloAria: string;
}) {
  const largura = estagios.length * LARGURA_TILE;
  return (
    <svg className="azulejo-faixa" viewBox={`0 0 ${largura} ${ALTURA}`} role="img" aria-label={rotuloAria}>
      {estagios.map((estagio, i) => {
        const x = i * LARGURA_TILE + 8;
        const cx = x + TAMANHO_ICONE / 2;
        const cy = 8 + TAMANHO_ICONE / 2;
        return (
          <g key={estagio.rotulo}>
            <rect
              x={x}
              y={8}
              width={TAMANHO_ICONE}
              height={TAMANHO_ICONE}
              rx={10}
              className={`azulejo-tile azulejo-tile--${estagio.situacao}`}
            />
            {estagio.situacao === "pendente" ? (
              <circle cx={cx} cy={cy} r={20} className="azulejo-marca azulejo-marca--pendente" fill="none" strokeDasharray="6 7" />
            ) : (
              <circle
                cx={cx}
                cy={cy}
                r={estagio.situacao === "ativo" ? 11 : 20}
                className={`azulejo-marca${estagio.situacao === "ativo" ? " azulejo-marca--ativo" : ""}`}
              />
            )}
          </g>
        );
      })}
      <g fontFamily="IBM Plex Mono, monospace" fontSize="12">
        {estagios.map((estagio, i) => (
          <text
            key={estagio.rotulo}
            x={i * LARGURA_TILE + 8 + TAMANHO_ICONE / 2}
            y={ALTURA - 4}
            textAnchor="middle"
            className={estagio.situacao === "concluido" ? undefined : estagio.situacao}
          >
            {estagio.rotulo}
          </text>
        ))}
      </g>
    </svg>
  );
}
