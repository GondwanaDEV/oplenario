// AzulejoMini — variante compacta do AzulejoFaixa (Onda B Slice 1) para a coluna "Situação" da tabela de
// proposições (.azulejo-mini na tela-fonte, 13x13px por bloco). Mesma entrada (EstagioTramitacao[]) e mesma
// semântica visual do AzulejoFaixa (concluído = sólido; ativo = marca menor com acento; pendente =
// tracejado) — aqui SEM <text> por estágio (a tabela não tem espaço para rótulos; a acessibilidade vem do
// aria-label via descreverFaixa, chamado pelo caller).

import type { EstagioTramitacao } from "../tramitacao-vista";

const LADO = 13;
const GAP = 2;

export function AzulejoMini({
  estagios,
  rotuloAria,
}: {
  estagios: EstagioTramitacao[];
  rotuloAria: string;
}) {
  const largura = estagios.length * LADO + (estagios.length - 1) * GAP;
  return (
    <svg
      className="azulejo-mini"
      width={largura}
      height={LADO}
      viewBox={`0 0 ${largura} ${LADO}`}
      role="img"
      aria-label={rotuloAria}
    >
      {estagios.map((estagio, i) => (
        <rect
          key={estagio.rotulo}
          x={i * (LADO + GAP)}
          y={0}
          width={LADO}
          height={LADO}
          rx={3}
          className={`azulejo-mini-bloco azulejo-mini-bloco--${estagio.situacao}`}
        />
      ))}
    </svg>
  );
}
