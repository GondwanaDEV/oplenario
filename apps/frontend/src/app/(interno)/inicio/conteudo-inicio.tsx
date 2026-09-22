"use client";

// ConteudoInicio — o corpo de /inicio. Compõe os hooks (papéis + sessões) e entrega a vista derivada ao
// PainelInicio. O split é o mesmo de conteudo-agendar-sessao.tsx: aqui mora o IO, lá o desenho.
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
import "./inicio.css";

export function ConteudoInicio() {
  const { token } = useAuth();
  const { papeis, estado: estadoPapeis } = usePapeis();
  const { sessoes, estado: estadoSessoes } = useSessoes(token);

  // Segura o render enquanto /eu não respondeu — sem isso a tela decidiria a persona com `papeis=[]` e
  // piscaria a home do cidadão para a secretária (mesmo racional dos guards de papel).
  if (estadoPapeis === "carregando") return null;

  const vista = derivarInicio({ papeis, sessoes, estadoSessoes });

  return (
    <>
      {vista.persona === "secretaria" && <TopoInterno area="Início" />}
      <PainelInicio vista={vista} />
    </>
  );
}
