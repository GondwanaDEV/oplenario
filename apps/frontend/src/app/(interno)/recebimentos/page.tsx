"use client";

// Rota /recebimentos (interno) — a fila de CARGAS não recebidas da Casa (fatia 2b, pedido do stakeholder: "toda
// movimentação do documento assinada por quem recebe"). Gate de papel via <GuardSecretaria> (a authz REAL é o
// backend: exige-papel "secretario" em GET /legislativo/recebimentos-pendentes e no POST de recebimento).

import { GuardSecretaria } from "../guard-secretaria";
import { ConteudoRecebimentos } from "./conteudo-recebimentos";

export default function PaginaRecebimentos() {
  return (
    <GuardSecretaria>
      <ConteudoRecebimentos />
    </GuardSecretaria>
  );
}
