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
// o rótulo é o nome que a Casa deu à etapa e pode ser longo ("Aguardando designação de relator"): quebra em linhas
// de até 14 caracteres (mono 12px ≈ 7 px/caractere, o tile tem ~112 px úteis) em vez de invadir o tile vizinho.
const CARACTERES_POR_LINHA = 14;
const LINHAS_MAX = 3;
const ENTRELINHA = 14;
// a partir daqui a faixa não encolhe mais para caber (texto ilegível): o contêiner rola na horizontal
const ETAPAS_PARA_ROLAR = 6;

/** Quebra o rótulo em linhas por palavra; palavra maior que a linha, ou excesso de linhas, termina em "…" (o rótulo
 * inteiro continua no `aria-label` da faixa). Rótulo curto devolve uma linha só, igual ao de entrada. */
export function quebrarRotulo(rotulo: string): string[] {
  const palavras = rotulo.trim().split(/\s+/).filter(Boolean);
  const cortar = (p: string) => (p.length > CARACTERES_POR_LINHA ? `${p.slice(0, CARACTERES_POR_LINHA - 1)}…` : p);
  const linhas: string[] = [];
  let atual = "";
  for (const palavra of palavras) {
    const candidata = atual ? `${atual} ${palavra}` : palavra;
    if (candidata.length <= CARACTERES_POR_LINHA) {
      atual = candidata;
    } else {
      if (atual) linhas.push(atual);
      atual = palavra;
    }
  }
  if (atual) linhas.push(atual);
  if (linhas.length === 0) return [rotulo];
  const visiveis = linhas.slice(0, LINHAS_MAX).map(cortar);
  if (linhas.length > LINHAS_MAX) {
    const ultima = visiveis[LINHAS_MAX - 1];
    visiveis[LINHAS_MAX - 1] = ultima.endsWith("…") ? ultima : `${ultima.slice(0, CARACTERES_POR_LINHA - 1)}…`;
  }
  return visiveis;
}

export function AzulejoFaixa({
  estagios,
  rotuloAria,
}: {
  estagios: EstagioTramitacao[];
  rotuloAria: string;
}) {
  const largura = estagios.length * LARGURA_TILE;
  const linhasDe = estagios.map((e) => quebrarRotulo(e.rotulo));
  const altura = ALTURA + (Math.max(1, ...linhasDe.map((l) => l.length)) - 1) * ENTRELINHA;
  return (
    // width/height intrínsecos (mesmo padrão do AzulejoMini): sem eles o SVG não tem tamanho natural e o CSS
    // era forçado a `width:100%`, o que ESTICAVA a faixa à largura do container — com poucos estágios os
    // tiles viravam gigantes. Com o tamanho intrínseco aqui, o CSS só precisa limitar (`max-width:100%`): a
    // faixa renderiza no tamanho de projeto (tile ~120px) e encolhe proporcionalmente só quando falta largura.
    <svg
      className={estagios.length > ETAPAS_PARA_ROLAR ? "azulejo-faixa azulejo-faixa--larga" : "azulejo-faixa"}
      width={largura}
      height={altura}
      viewBox={`0 0 ${largura} ${altura}`}
      role="img"
      aria-label={rotuloAria}
    >
      {estagios.map((estagio, i) => {
        const x = i * LARGURA_TILE + 8;
        const cx = x + TAMANHO_ICONE / 2;
        const cy = 8 + TAMANHO_ICONE / 2;
        return (
          <g key={estagio.chave ?? estagio.rotulo}>
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
        {estagios.map((estagio, i) => {
          const x = i * LARGURA_TILE + 8 + TAMANHO_ICONE / 2;
          const linhas = linhasDe[i];
          return (
            <text
              key={estagio.chave ?? estagio.rotulo}
              x={x}
              y={ALTURA - 4}
              textAnchor="middle"
              className={estagio.situacao === "concluido" ? undefined : estagio.situacao}
            >
              {linhas.length === 1
                ? estagio.rotulo
                : linhas.map((linha, j) => (
                    <tspan key={j} x={x} dy={j === 0 ? 0 : ENTRELINHA}>
                      {linha}
                    </tspan>
                  ))}
            </text>
          );
        })}
      </g>
    </svg>
  );
}
