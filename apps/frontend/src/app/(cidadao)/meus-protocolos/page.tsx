"use client";

// /meus-protocolos (formulários do cidadão, ADR-0015) — o que a cidadã protocolou (e-SIC, LGPD, ouvidoria
// identificada), com estado, prazo e resposta, e o recurso do e-SIC. Mesmo chrome de /acompanhamentos.
// ADR-0021: e as inscrições para falar em audiência pública — fonte PRÓPRIA, que falha sozinha (os protocolos seguem).

import { useAuth } from "@/lib/auth";
import { useMeusProtocolos } from "@/lib/use-meus-protocolos";
import { ListaProtocolos } from "./lista-protocolos";
import { InscricoesAudiencia } from "./inscricoes-audiencia";
import "../acompanhamentos/acompanhamentos.css";
import "./meus-protocolos.css";

export default function PaginaMeusProtocolos() {
  const { token } = useAuth();
  const { dados, estado, recarregar } = useMeusProtocolos(token);

  return (
    <>
      <a className="pular" href="#conteudo">
        Pular para o conteúdo
      </a>
      <div id="conteudo" className="envelope ac-pagina">
        <header className="ac-cab">
          <p className="eyebrow">Portal do Cidadão</p>
          <h1>Meus protocolos</h1>
          <p className="ac-nota">
            Seus pedidos de informação, pedidos sobre os seus dados, manifestações à Ouvidoria e inscrições para falar
            em audiências públicas nesta Câmara.
          </p>
        </header>

        {estado === "carregando" && (
          <p className="ac-nota" aria-live="polite">
            Carregando…
          </p>
        )}
        {estado === "erro" && (
          <p className="ac-nota ac-erro" role="alert">
            Não foi possível carregar os seus protocolos agora. Tente novamente em instantes.
          </p>
        )}
        {estado === "pronto" && dados && <ListaProtocolos dados={dados} token={token} aoMudar={recarregar} />}
        <InscricoesAudiencia token={token} />
      </div>
    </>
  );
}
