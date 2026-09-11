"use client";

// "O que vence" — porta .prazos de paineis-mesa.html, agora unindo obrigações de compliance (em-aberto)
// com pendências de atendimento ao cidadão (e-SIC/LGPD/ouvidoria), ordenado por vence-em. Usa AnelPrazo.
//
// DESVIO do draft do brief: o draft acessava templateChave/objetoTipo/protocolo via type-cast solto
// ((item as { templateChave: string }).templateChave). O tipo REAL de vista.itens[number] (inferido do
// retorno de derivarMesaVista em mesa-vista.ts) já é uma união discriminada por `origem` — TS narrowa
// corretamente com `item.origem === "compliance"` sem qualquer cast, contanto que o branch de compliance
// (ComplianceCard.emAberto) declare os campos reais (templateChave etc.) e não só venceEm. Isso exigiu
// alargar essa interface local em mesa-vista.ts (Task B5) para espelhar ObrigacaoEmAbertoOut de verdade —
// ver o comentário lá. Com isso, `item.templateChave` / `item.objetoTipo` / `item.protocolo` acessam
// direto, com checagem estática de verdade em vez de um cast que mascarava a falta do campo.

import { AnelPrazo } from "@/lib/charts/anel-prazo";
import { rotularObjetoPrazo } from "@/lib/mesa-vista";
import type { MesaVista } from "@/lib/mesa-vista";

function diasAte(dataIso: string): number {
  const alvo = new Date(dataIso).getTime();
  const hoje = new Date().getTime();
  return Math.max(0, Math.ceil((alvo - hoje) / (1000 * 60 * 60 * 24)));
}

export function OQueVence({ vista }: { vista: MesaVista["oQueVence"] }) {
  if (vista.estado === "indisponivel") {
    return (
      <section className="bloco" aria-labelledby="prazos-titulo">
        <div className="bloco-cabeca"><h2 id="prazos-titulo">O que vence</h2></div>
        <div className="bloco-corpo"><p>Indisponível no momento.</p></div>
      </section>
    );
  }
  return (
    <section className="bloco" aria-labelledby="prazos-titulo">
      <div className="bloco-cabeca">
        <h2 id="prazos-titulo">O que vence</h2>
        <span className="selo-n mono">{vista.itens.length} itens</span>
      </div>
      <div className="bloco-corpo">
        {/* GET /compliance/painel corta `em-aberto` no teto server-side; `truncamentoCompliance` é o
            total AUTORITATIVO que o servidor publica (`emAbertoTotal`), não uma dedução do front. Só a
            fatia de compliance tem esse sinal — pendências de atendimento (e-SIC/LGPD/ouvidoria) não
            carregam um total equivalente, por isso o aviso nomeia "obrigações do TCE", não a lista
            inteira. Nunca fingir completude: quem lê precisa saber que pode haver prazo mais distante
            fora desta página. */}
        {vista.truncamentoCompliance && (
          <p role="status" className="aviso-corte">
            Mostrando <b>{vista.truncamentoCompliance.exibidos} de {vista.truncamentoCompliance.total}</b>{" "}
            obrigações do TCE em aberto — pode haver prazos mais distantes fora desta lista. Confira o
            painel de compliance completo.
          </p>
        )}
        <ul className="prazos">
          {vista.itens.map((item) => {
            const dias = diasAte(item.venceEm);
            const rotulo =
              item.origem === "compliance"
                ? `Obrigação TCE · ${item.templateChave}`
                : `${rotularObjetoPrazo(item.objetoTipo)} · ${item.protocolo}`;
            return (
              // Chave ESTAVEL, nao o indice: `vista.itens` e' recomposta de duas fontes e reordenada
              // por `venceEm`, entao um prazo novo mais urgente entra no meio e desloca todos os
              // indices seguintes — o React reaproveitaria o <li> errado.
              <li key={item.origem === "compliance" ? item.id : item.objetoId} className="prazo-item">
                <AnelPrazo diasRestantes={dias} diasTotal={30} rotulo={rotulo} />
                <div className="prazo-obj">
                  <b>{rotulo}</b>
                  <span className="quando">vence em {dias} dia(s)</span>
                </div>
              </li>
            );
          })}
        </ul>
        {vista.itens.length === 0 && <p>Nenhum prazo em aberto.</p>}
      </div>
    </section>
  );
}
