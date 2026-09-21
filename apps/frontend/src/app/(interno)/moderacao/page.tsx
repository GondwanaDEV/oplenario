"use client";

// Rota /moderacao (interno) — a fila de moderação de comentários (GAP docs/20 → tela de servidor). Gate de
// papel via <GuardSecretaria> (a authz REAL é o backend: exige-papel "secretario" nas rotas
// GET /moderacao/comentarios e POST /comentarios/:id/moderar; o guard só evita mostrar o erro cru a quem
// não tem o papel — mesma disciplina de expediente/cadastros).

import { GuardSecretaria } from "../guard-secretaria";
import { ConteudoModeracao } from "./conteudo-moderacao";

export default function PaginaModeracao() {
  return (
    <GuardSecretaria>
      <ConteudoModeracao />
    </GuardSecretaria>
  );
}
