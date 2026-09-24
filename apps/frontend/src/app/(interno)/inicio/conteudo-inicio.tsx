"use client";

// ConteudoInicio — o corpo de /inicio. Decide a persona pelos papéis e entrega cada uma à sua home:
//   · secretaria → a CENTRAL DA CASA (docs/23 Fatia 3): o cockpit do operador, com o próprio IO (useCentral);
//   · vereador / sem área → PainelInicio, alimentado aqui por useSessoes + derivarInicio.
// Cada ramo é um componente próprio para que só a home escolhida faça as suas buscas.
//
// Sem <GuardSecretaria> DE PROPÓSITO: esta é a porta de entrada de QUALQUER pessoa autenticada — é ela que
// resolve "para onde eu vou". Gatear por `secretario` devolveria "Acesso restrito" ao vereador que acabou
// de logar, que é exatamente o beco que esta tela existe para fechar. A authz real continua sendo do
// backend em cada tela de destino; aqui só se decide o que MOSTRAR.
//
// A barra de navegação interna (TopoInterno) é o menu da SECRETARIA — só aparece para ela. O vereador vê
// um cabeçalho simples e é mandado para a área dele.

import { useAuth, usePapeis } from "@/lib/auth";
import { useSessoes } from "@/lib/use-sessoes";
import { derivarInicio } from "@/lib/inicio-vista";
import { TopoInterno } from "../topo";
import { PainelInicio } from "./painel-inicio";
import { CentralDaCasa } from "./central-da-casa";
import "./inicio.css";

function InicioPessoal({ token, papeis }: { token: string | null; papeis: string[] }) {
  const { sessoes, estado: estadoSessoes } = useSessoes(token);
  return <PainelInicio vista={derivarInicio({ papeis, sessoes, estadoSessoes })} />;
}

export function ConteudoInicio() {
  const { token } = useAuth();
  const { papeis, estado: estadoPapeis } = usePapeis();

  // Segura o render enquanto /eu não respondeu — sem isso a tela decidiria a persona com `papeis=[]` e
  // piscaria a home do cidadão para a secretária (mesmo racional dos guards de papel).
  if (estadoPapeis === "carregando") return null;

  // `secretario` vence quando alguém acumula papéis — a mesma regra de `personaDe` em inicio-vista.ts.
  if (papeis.includes("secretario")) {
    return (
      <>
        <TopoInterno area="Central da Casa" />
        <CentralDaCasa token={token} />
      </>
    );
  }
  return <InicioPessoal token={token} papeis={papeis} />;
}
