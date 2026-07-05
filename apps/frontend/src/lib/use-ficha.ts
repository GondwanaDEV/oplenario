"use client";

// Hook do Portal do Cidadão (Task 3.2, Fatia A2.3) — busca GET /api/portal/casa/{ente}/materias/
// {proposicaoId} (a ficha) + .../comentarios (lista pública, só aprovados) EM PARALELO. Mesmo DESVIO já
// documentado em use-materias.ts/use-encarregado.ts (fetch relativo não resolve em Server Component; o
// rewrite same-origin só existe para requests do browser).
//
// Degradação POR CHAMADA (Global Constraints — "degradação por seção"): a ficha ausente (404/erro) é o
// único caso que vira estado "erro" — sem a matéria não há o que mostrar. Comentários falhos NUNCA
// esvaziam a ficha: `comentarios` fica `null` e a seção de comentários mostra o próprio estado honesto
// (ver secao-ficha.tsx), o resto da página segue "pronta".

import { useEffect, useState } from "react";
import { buscarPublico } from "./portal-api";
import type { FichaOut } from "./contrato-portal.gen";
import type { ComentarioOut } from "./ficha-vista";

type Estado = "carregando" | "pronto" | "erro";

export function useFicha(ente: string, proposicaoId: string) {
  const [ficha, setFicha] = useState<FichaOut | null>(null);
  const [comentarios, setComentarios] = useState<ComentarioOut[] | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");
  const [enteAnterior, setEnteAnterior] = useState(ente);
  const [idAnterior, setIdAnterior] = useState(proposicaoId);

  // reset cross-matéria/tenant DURANTE O RENDER (mesmo padrão de use-materias.ts/use-encarregado.ts,
  // review A2.1 item 1) — DOIS estados-anterior independentes (review A2.3 item 1: uma chave concatenada
  // `${ente}/${proposicaoId}` colide quando o `/` aparece dentro de um segmento decodificado, deixando
  // passar o flash de matéria obsoleta). Trocar qualquer um dos dois (ex. clicar noutra matéria da mesma
  // câmara) precisa limpar o resultado anterior antes do novo fetch.
  if (ente !== enteAnterior || proposicaoId !== idAnterior) {
    setEnteAnterior(ente);
    setIdAnterior(proposicaoId);
    setFicha(null);
    setComentarios(null);
    setEstado("carregando");
  }

  useEffect(() => {
    let vivo = true;
    (async () => {
      const [f, c] = await Promise.all([
        buscarPublico<FichaOut>(ente, "materias", proposicaoId),
        buscarPublico<ComentarioOut[]>(ente, "materias", proposicaoId, "comentarios"),
      ]);
      if (!vivo) return;
      if (!f) {
        setEstado("erro");
        return;
      }
      setFicha(f);
      setComentarios(c); // null se o fetch de comentários falhou — degrada só aquela seção
      setEstado("pronto");
    })();
    return () => {
      vivo = false;
    };
  }, [ente, proposicaoId]);

  return { ficha, comentarios, estado };
}
