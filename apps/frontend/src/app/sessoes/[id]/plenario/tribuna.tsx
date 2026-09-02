"use client";

// A TRIBUNA do painel ao vivo — quem está com a palavra, por quanto tempo, e quem é o próximo.
//
// Extraída de `page.tsx` por DUAS razões, nesta ordem:
//
// 1. Enquanto era uma função local não-exportada, nenhum teste conseguia alcançá-la. A revisão
//    adversarial da branch anterior registrou isso como dívida explícita: `nomeFase` estava provada
//    como função pura, e nada provava que a tribuna a CHAMAVA — um refactor podia reintroduzir a chave
//    crua no telão com a CI verde. Aqui ela é exportada e testada pelo texto renderizado.
// 2. É o bloco que mais mudou nesta fatia (resolução de nome), e `page.tsx` já concentra Topo, Fases,
//    Palco, Quórum e placar. O painel da Mesa já usa um arquivo por bloco — esta é a convenção da casa.
//
// O DEFEITO que esta fatia corrige: o evento SSE carrega só `orador-id`/`vereador-id`, e o componente
// não resolvia nome nenhum. O avatar mostrava dois caracteres do UUID ("E9") e a fila de inscritos, o
// prefixo dele ("64d38c04"). Num telão de sessão real isso aparece no lugar do nome do parlamentar.

import { segundosDecorridos, formatarTempo } from "@/lib/cronometro";
import { iniciais } from "@/lib/iniciais";
import { identidadeDe, type EstadoPlenario } from "@/lib/plenario-reducer";
import { nomeFase, nomeTipoFala } from "@/lib/rotulos-sessao";

/** O rótulo neutro de quem está na tribuna sem nome resolvido. É VERDADEIRO nos três casos em que cai:
 * a composição ainda não chegou, a busca dela falhou, ou o id não é de membro da Casa. Este último é
 * legítimo e não excepcional — a fase `tribuna_livre_cidadao` existe, e `orador-id` não tem FK para
 * vereador. Por isso o rótulo NÃO é "não identificado": afirmar isso de um cidadão na tribuna livre
 * seria falso, e o defeito que estamos corrigindo é exatamente a tela afirmar identidade que não tem. */
const ORADOR_SEM_NOME = "Orador com a palavra";
const INSCRITO_SEM_NOME = "Inscrito";

/** Glifo do avatar quando não há nome. Nunca caracteres do UUID: um id truncado LÊ como identidade
 * ("E9"), e era essa leitura falsa o defeito. Um traço não afirma nada. */
const AVATAR_SEM_NOME = "—";

export function Tribuna({ estado, agora }: { estado: EstadoPlenario; agora: number }) {
  const o = estado.oradorAtual;
  const pausado =
    estado.marcosCronometro.length > 0 &&
    estado.marcosCronometro[estado.marcosCronometro.length - 1].tipo === "pausada";
  // aritmética pura por tick — sem useMemo (a dep `agora` muda a cada segundo, a memo nunca acertaria)
  const decorrido = o ? segundosDecorridos(o.iniciouEm, estado.marcosCronometro, agora) : 0;

  const idOrador = o ? identidadeDe(estado, o.oradorId) : null;
  const nomeOrador = idOrador?.nomeParlamentar ?? null;

  return (
    <section className="bloco larga" aria-labelledby="tribuna-titulo">
      <div className="bloco-cabeca">
        <h2 id="tribuna-titulo">Tribuna</h2>
        <span className="eyebrow" style={{ color: "var(--texto-2)" }}>
          {o ? nomeFase(o.fase) : "livre"}
        </span>
      </div>
      <div className="bloco-corpo">
        {o ? (
          <>
            <div className="tribuna-quem">
              <span className="avatar av" aria-hidden="true">
                {nomeOrador ? iniciais(nomeOrador) : AVATAR_SEM_NOME}
              </span>
              <div>
                {/* `title` com o id só quando NÃO há nome: o operador da Mesa precisa de algo para
                    casar com o registro quando o cadastro está incompleto, e o público não lê tooltip.
                    O id nunca entra no texto visível. */}
                <b title={nomeOrador ? undefined : o.oradorId}>{nomeOrador ?? ORADOR_SEM_NOME}</b>
                <span>
                  {nomeTipoFala(o.tipoFala)}
                  {idOrador?.cargoMesa ? ` · ${idOrador.cargoMesa}` : ""}
                </span>
              </div>
            </div>
            <div className="tribuna-tempo">
              <span className="rotulo">{pausado ? "Pausado" : "No uso da palavra"}</span>
              <span
                className={`timer ${pausado ? "pausado" : ""}`}
                role="timer"
                aria-label="Tempo de tribuna"
              >
                {formatarTempo(decorrido)}
              </span>
            </div>
          </>
        ) : (
          <p className="tribuna-vazia">Ninguém com a palavra no momento.</p>
        )}
        {estado.inscritos.length > 0 && (
          <ol className="inscritos" aria-label="Inscritos">
            {estado.inscritos.map((i) => {
              const nome = identidadeDe(estado, i.vereadorId)?.nomeParlamentar ?? null;
              return (
                <li key={i.inscricaoId}>
                  <span className="ord">{i.ordem}</span>
                  <b title={nome ? undefined : i.vereadorId}>{nome ?? INSCRITO_SEM_NOME}</b>
                </li>
              );
            })}
          </ol>
        )}
      </div>
    </section>
  );
}
