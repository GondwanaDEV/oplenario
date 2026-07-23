"use client";

// Hook do PERFIL PÚBLICO DO VEREADOR (Onda E fatia 2, Task 5) — GET /api/portal/casa/{ente}/vereadores/
// {vereadorId}. Mesmo DESVIO já documentado em use-materias.ts/use-ficha.ts: um fetch relativo não resolve
// em Server Component (o rewrite same-origin de next.config.ts só existe para requests do browser), então
// o fetch mora aqui e a page.tsx fica fina.
//
// UMA chamada só, e é correto que a página inteira dependa dela: o perfil é um recurso único. Não há
// "degradação por seção" a fazer aqui — 404 e falha de rede colapsam no mesmo null (buscarPublico), e a
// borda NÃO PODE afirmar qual dos dois ocorreu. O 404 do backend é fail-closed e colapsa deliberadamente
// "não existe" com "é de outra Casa" (separá-los na tela vazaria filiação cross-Casa numa rota anônima).
//
// `nomeParlamentar === null` NUNCA vira estado "erro": é apelido ausente, NULL de primeira classe —
// derivar erro dele reabriria o oráculo de existência que o `:maybe` do contrato fechou.

import { useEffect, useState } from "react";
import { buscarPublico } from "./portal-api";
import type { PerfilVereadorOut } from "./contrato-portal.gen";

type Estado = "carregando" | "pronto" | "erro";

export function usePerfilVereador(ente: string, vereadorId: string) {
  const [perfil, setPerfil] = useState<PerfilVereadorOut | null>(null);
  const [estado, setEstado] = useState<Estado>("carregando");
  const [enteAnterior, setEnteAnterior] = useState(ente);
  const [idAnterior, setIdAnterior] = useState(vereadorId);

  // reset cross-vereador/cross-tenant DURANTE O RENDER (não dentro do efeito): a flag `vivo` só evita
  // escrita fora de ordem, não limpa estado já commitado — sem isto, trocar de perfil mostra o vereador
  // anterior por um frame, sob o nome errado. DOIS estados-anterior independentes (review A2.3 item 1):
  // uma chave concatenada `${ente}/${id}` colide quando "/" aparece dentro de um segmento decodificado.
  // (setState síncrono no topo do useEffect é proibido pelo eslint-plugin-react-hooks v7.)
  if (ente !== enteAnterior || vereadorId !== idAnterior) {
    setEnteAnterior(ente);
    setIdAnterior(vereadorId);
    setPerfil(null);
    setEstado("carregando");
  }

  useEffect(() => {
    let vivo = true;
    (async () => {
      // `buscarPublico` recebe SEGMENTOS (codifica cada um contra `../`) e já aplica `camelizarChaves` —
      // nunca um caminho concatenado, nunca `fetch` cru.
      const p = await buscarPublico<PerfilVereadorOut>(ente, "vereadores", vereadorId);
      if (!vivo) return;
      if (!p) {
        setEstado("erro");
        return;
      }
      setPerfil(p);
      setEstado("pronto");
    })();
    return () => {
      vivo = false;
    };
  }, [ente, vereadorId]);

  return { perfil, estado };
}
