// AcoesCard — card "Ações" do rail (Onda B Slice 3). Porte de ficha-materia.html:255-271. Nenhuma das 3
// ações tem backend fiado nesta fatia (Incluir na pauta = módulo pauta; Gerar ficha PDF = exportação;
// Distribuir a comissão = fluxo de distribuição) — mesma disciplina de BalcaoLgpd (balcao-lgpd.tsx):
// botões INERTES (disabled + aria-disabled) em vez de fingir que funcionam, com um único <EmBreve>
// honesto explicando o porquê (Global Constraints "sem dado falso").

import { EmBreve } from "@/lib/em-breve";

const ACOES = ["Incluir na pauta", "Gerar ficha PDF", "Distribuir a comissão"];

export function AcoesCard() {
  return (
    <div className="card">
      <h3>Ações</h3>
      <div className="card-acoes">
        {ACOES.map((rotulo) => (
          <button
            key={rotulo}
            className="btn btn-contorno"
            type="button"
            disabled
            aria-disabled="true"
            aria-describedby="ficha-acoes-embreve"
          >
            {rotulo}
          </button>
        ))}
      </div>
      <div id="ficha-acoes-embreve">
        <EmBreve
          titulo="Ações da matéria"
          motivo="Incluir na pauta, gerar a ficha em PDF e distribuir a comissão ainda não têm o backend fiado nesta fatia — chegam em fatias futuras (pauta, exportação, distribuição)."
        />
      </div>
    </div>
  );
}
