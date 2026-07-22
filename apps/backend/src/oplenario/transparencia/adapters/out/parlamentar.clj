(ns oplenario.transparencia.adapters.out.parlamentar
  "Gate de SAIDA `models -> wire/out` do PERFIL PUBLICO do vereador (§22.10 adapters/out, ADR-0001; Onda E
  fatia 2, Task 4) — chamado SO pelo diplomat/.

  FUNDE DUAS FONTES que nunca se tocam no dominio:
  - `ficha` = a IDENTIDADE, vinda de `cadastros` pelo seam `ficha-e-janelas-publicas` INJETADO pelo host
    (inversao de dependencia; `transparencia` nunca importa `cadastros` — o `arquitetura_test` quebra se
    importar). Mesma forma que `cadastros/Repo/ficha-vereador` devolve: {:vereador :mandato :legislatura
    :comissoes}. Este ns NAO importa `cadastros/adapters/out/vereador` (seria import cross-modulo E
    adapters->adapters, ambos proibidos): a projecao da identidade e' local, poucos campos.
  - `perfil` = os NUMEROS, do read-model proprio (`controllers/perfil-parlamentar`).

  Validado contra `wire/PerfilVereadorOut` (`:closed` em todos os niveis): drift de campo e' bug de servidor
  -> 500, NUNCA resposta publica malformada — nesta rota, campo a mais e' vazamento."
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.transparencia.wire.out.parlamentar :as wire]))

(set! *warn-on-reflection* true)

(def acervo-com-elo-de-autoria-desde
  "Data em que o elo autoria->vereador (`transparencia.materia.autor_id`, mig 20260720000063) passou a ser
  projetado. Materia protocolada ANTES disso tem `autor_id` NULL e NAO aparece em perfil nenhum: a projecao
  nao tem ferramenta de replay (carry registrado desde a mig 0044). Sai em toda resposta para a UI declarar
  o recorte em vez de exibir uma lista incompleta como se fosse o acervo inteiro. E' CONSTANTE de deploy,
  nao dado de banco — muda so' se um backfill for feito."
  "2026-07-20")

(def presenca-projetada-desde
  "Data em que `transparencia.presenca_parlamentar` passou a ser projetada (mig 20260720000064). Irma de
  `acervo-com-elo-de-autoria-desde`, e pelo mesmo motivo: nao ha ferramenta de re-projecao no repo, entao
  sessao com chamada ANTERIOR a esta data simplesmente nao existe no read-model.

  ELA PASSOU A IMPORTAR NA FATIA 6 DO I-5. Com o denominador recortado pela janela de exercicio, um mandato
  que comecou antes desta data recebe um denominador MENOR que a realidade — some dos DOIS lados da fracao,
  nunca vira falta, mas o '12 de 12' de um mandato de 2021 nao e' o mandato inteiro. Sem este campo a UI nao
  teria como declarar o recorte, e o `:closed` do wire fecha qualquer outra via de descobri-lo.

  E' CONSTANTE de deploy, nao dado de banco. Se um dia houver backfill de acervo de presenca, e' AQUI que a
  data muda — e a companheira `sessao_com_chamada` tem reconciliador proprio (mig 0068), que nao recua esta
  fronteira: ele so' re-deriva o que ja' esta' em `presenca_parlamentar`."
  "2026-07-20")

(defn- ->str [x] (some-> x str))

(defn- validar! [out]
  (when-not (m/validate wire/PerfilVereadorOut out)
    (throw (ex-info "projecao viola o contrato PerfilVereadorOut (bug de servidor)"
                    {:erros (me/humanize (m/explain wire/PerfilVereadorOut out))})))
  out)

(defn- cargo-mesa-de
  "UMA fonte para o cargo de Mesa: a entrada de `comissoes` cujo tipo e' 'mesa' — nunca lido do mandato
  (mesmo contrato de `cadastros/adapters/out/vereador`, para os dois lados nao divergirem)."
  [comissoes]
  (some #(when (= "mesa" (:tipo %)) (:cargo %)) comissoes))

(defn- legislatura->wire [leg]
  (when (and leg (:numero leg))
    {:numero (:numero leg) :ano-inicio (:ano-inicio leg) :ano-fim (:ano-fim leg)}))

(defn- materia->wire [m]
  {:proposicao-id (->str (:proposicao-id m)) :tipo (:tipo m) :ano (:ano m)
   :sequencial (:sequencial m) :ementa (:ementa m) :estado (:estado m)})

(defn- materia-rotulo
  "'projeto_lei 12/2026' — rotulo legivel do objeto votado, montado do tipo/sequencial/ano da materia
  LEFT-JOINada. `nil` quando a materia nao esta' projetada (o LEFT JOIN nao casou): melhor voto sem rotulo
  que voto omitido."
  [v]
  (when (and (:materia-tipo v) (:materia-sequencial v) (:materia-ano v))
    (str (:materia-tipo v) " " (:materia-sequencial v) "/" (:materia-ano v))))

(defn- voto->wire [v]
  {:votacao-id (->str (:votacao-id v)) :voto (:voto v) :ocorrido-em (->str (:ocorrido-em v))
   :materia-rotulo (materia-rotulo v) :materia-ementa (:materia-ementa v)})

(defn ->wire
  "{:vereador :mandato :legislatura :comissoes} (identidade, seam do host) +
  {:materias :materias-total :normas-de-autoria :votos :votos-total :presenca} (read-model)
  -> PerfilVereadorOut.

  `vereador-id` sai da FICHA, nao do path: se o seam devolveu ficha, ele e' a autoridade sobre quem e' este
  parlamentar nesta Casa.

  `:nome-parlamentar` e' pass-through CRU de proposito — apelido ausente e' NULL de primeira classe no
  cadastro e sai NULL aqui (o wire e' `:maybe`). Nao ha `(or ... nome)`: o fallback para o nome civil e'
  decisao de APRESENTACAO, e faze-lo no servidor apagaria a distincao entre 'nao tem apelido' e 'o apelido
  e' igual ao nome' (achado C-1, revisao Task 4)."
  [{:keys [vereador legislatura comissoes] :as _ficha}
   {:keys [materias materias-total normas-de-autoria votos votos-total presenca] :as _perfil}]
  (validar!
   {:vereador-id       (->str (:id vereador))
    :nome-parlamentar  (:nome-parlamentar vereador)
    :nome-civil        (:nome vereador)
    :legislatura       (legislatura->wire legislatura)
    :cargo-mesa        (cargo-mesa-de comissoes)
    :comissoes         (mapv :nome comissoes)
    :materias          (mapv materia->wire materias)
    :materias-total    (or materias-total 0)
    :normas-de-autoria (or normas-de-autoria 0)
    :votos             (mapv voto->wire votos)
    :votos-total       (or votos-total 0)
    ;; `:janela-de-exercicio-conhecida` passa por `true?` so' para satisfazer o `:boolean` do wire — o
    ;; read-model SEMPRE devolve o campo (os dois ramos de `resumo-presenca` o escrevem). O default de um
    ;; nil inesperado e' `false` = "sem periodo de exercicio registrado", que e' o lado seguro: a tela para
    ;; de exibir fracao em vez de publicar um denominador em que nao se pode confiar.
    :presenca          {:sessoes-presente    (or (:sessoes-presente presenca) 0)
                        :sessoes-com-chamada (or (:sessoes-com-chamada presenca) 0)
                        :janela-de-exercicio-conhecida (true? (:janela-de-exercicio-conhecida presenca))}
    :acervo-com-elo-de-autoria-desde acervo-com-elo-de-autoria-desde
    :presenca-projetada-desde presenca-projetada-desde}))
