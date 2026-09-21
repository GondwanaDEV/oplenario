"use client";

// Rota /inicio — a TELA INICIAL da plataforma para quem está autenticado (GAP achado na apresentação
// guiada, docs/21: depois do login caía-se em `/`, a capa "front-end em construção", e daí só se avançava
// digitando URL). Fina: delega para ConteudoInicio, que decide a persona. AuthProvider + TemaProvider vêm
// do layout do grupo (interno).

import { ConteudoInicio } from "./conteudo-inicio";

export default function PaginaInicio() {
  return <ConteudoInicio />;
}
