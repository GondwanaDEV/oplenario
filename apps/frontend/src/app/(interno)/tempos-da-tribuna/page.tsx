"use client";

// Rota /tempos-da-tribuna (interno) — a tabela de tempos da tribuna da Casa (pedido do stakeholder: "3 min e
// adicionais de 1 min"). Gate de papel via <GuardSecretaria> (a authz REAL é o backend: exige-papel
// "secretario" em GET/PUT /tempos-regimentais; o guard só evita mostrar o erro cru a quem não tem o papel).

import { GuardSecretaria } from "../guard-secretaria";
import { ConteudoTemposTribuna } from "./conteudo-tempos-tribuna";

export default function PaginaTemposTribuna() {
  return (
    <GuardSecretaria>
      <ConteudoTemposTribuna />
    </GuardSecretaria>
  );
}
