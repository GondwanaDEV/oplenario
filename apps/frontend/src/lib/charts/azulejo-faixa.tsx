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
    // width/height intrínsecos (mesmo padrão do AzulejoMini): sem eles o SVG não tem tamanho natural e o CSS
    // era forçado a `width:100%`, o que ESTICAVA a faixa à largura do container — com poucos estágios os
    // tiles viravam gigantes. Com o tamanho intrínseco aqui, o CSS só precisa limitar (`max-width:100%`): a
    // faixa renderiza no tamanho de projeto (tile ~120px) e encolhe proporcionalmente só quando falta largura.
    <svg
      className="azulejo-faixa"
      width={largura}
      height={ALTURA}
      viewBox={`0 0 ${largura} ${ALTURA}`}
      role="img"
      aria-label={rotuloAria}
    >
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
      {/* a familia vem do token, nao cravada: a troca de tipografia de 22/09/2026 passou
          por aqui sem ser vista porque este valor estava fora do CSS. */}
      <g fontFamily="var(--mono)" fontSize="12">
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
