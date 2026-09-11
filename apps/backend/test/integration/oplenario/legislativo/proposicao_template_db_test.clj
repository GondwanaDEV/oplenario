(ns oplenario.legislativo.proposicao-template-db-test
  "INTEGRACAO (PG real): Fatia 1 da borda de tramitacao — o elo MATERIA <-> TEMPLATE.

  A engine do eixo C (`db/tramitacao/transicionar!`) sempre exigiu `template-id`, e a proposicao nunca
  teve onde guardar o seu: por isso a borda HTTP nunca existiu. Esta fatia so' constroi o elo — a
  proposicao passa a saber POR QUAL RITO ela corre — espelhando o que `legislativo.pareceres` ja' faz
  (`template_id` + FK same-tenant + estado inicial DERIVADO do template).

  A RESOLUCAO E' POR ESPECIE (mig 0077, decisao 1-A). A regra anterior — 'exatamente um rito ativo por
  Casa' — saiu: ela tratava 3+ ritos como config ambigua, e 3+ ritos e' o caso NORMAL de qualquer
  regimento (projeto de lei passa por comissao e dois turnos; requerimento vai direto a plenario; mocao
  nao tem parecer). Com ela, a Casa que cadastrasse o proprio regimento nao protocolava NADA.

  O que estes testes guardam:
  - Inv.4 (regra e' DADO): o `estado` da materia nova vem de `template.estado_inicial`, NUNCA de uma
    string cravada no codigo. As fixtures usam de proposito `estado-inicial` DIFERENTE de 'protocolada'
    (um teste que usasse 'protocolada' nao saberia distinguir o template do literal antigo).
  - Tres ritos, tres especies, tres caminhos — a terca-feira de Fortaleza.
  - A camada GENERICA (`template.tipo` NULL): o rito da Casa para as especies nao particularizadas, e o
    que todo acervo de hoje tem. Sem ela, introduzir a coluna faria toda Casa existente parar de
    tramitar EM SILENCIO.
  - Fail-closed DENTRO da camada: dois ritos ativos da MESMA especie (ou dois genericos) recusam em vez
    de o codigo escolher — e a recusa carrega `:tipo` no namespace `config`, senao vira 500 opaco.
  - Eixo H (numeracao gapless): a recusa acontece ANTES de consumir o sequencial — config errada nao
    abre buraco na numeracao oficial.
  - A valvula `aposentar-template!` existe NO PRODUTO: nenhuma fixture aqui fabrica `ativo = false`.
  - O elo tem dentes no BANCO tambem (FK same-tenant), nao so' no service."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [oplenario.cadastros.relacoes.cadastro :as rel-cad]
            [oplenario.config :as config]
            [oplenario.identidade.relacoes.identidade :as rel-id]
            [oplenario.kernel.components.datasource :as datasource]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.db.proposicao :as prop]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.migracao :as migracao]
            [oplenario.motor.components.registro-fatos :as rf])
  (:import (java.time LocalDate)))

(def ^:dynamic *ds* nil)
(def ^:dynamic *registro* nil)

(use-fixtures :once
  (fn [t]
    (let [c   (component/start (datasource/datasource (config/carregar)))
          reg (component/start (rf/registro-fatos (merge rel-cad/relacoes rel-id/relacoes)))]
      (migracao/migrar! (:ds c))
      (binding [*ds* (:ds c) *registro* reg]
        (try (t) (finally (component/stop reg) (component/stop c)))))))

(defn- template!
  "Cria um template de tramitacao do tenant (sujeito default 'proposicao') e devolve o id. `:tipo` omitido
  = rito GENERICO. `estado-inicial` de proposito NUNCA e' 'protocolada' nas fixtures que provam a
  derivacao. NAO ha' `:ativo`: template nasce ativo, e quem aposenta e' `aposentar-template!` — a fixture
  nao fabrica estado que o produto nao produz."
  [tx ente {:keys [chave estado-inicial sujeito tipo]}]
  (let [tid (random-uuid)]
    (tram/criar-template! tx {:id tid :ente-id ente :chave chave :versao 1
                              :nome (str "FIXTURE " chave) :estado-inicial estado-inicial
                              :sujeito sujeito :tipo tipo})
    tid))

(defn- protocolar! [tx ente m]
  (prop/protocolar! tx (merge {:id (random-uuid) :ente-id ente :tipo "projeto_lei" :ano 2026
                               :uf "CE" :municipio-nome "Fortaleza" :ementa "Dispoe sobre X"}
                              m)))

;; ---------------------------------------------------------------------------------------------------
;; O caminho feliz: o rito GENERICO (a forma de todo acervo existente) + o elo que a engine consome
;; ---------------------------------------------------------------------------------------------------

(deftest template-generico-unico-resolve-sozinho-e-da-o-estado-inicial
  ;; GUARDA: (1) o elo nasce preenchido na LINHA (nao so' no retorno); (2) o estado vem do TEMPLATE.
  ;; O rito aqui NAO declara especie — e' o rito generico, a forma que TODO acervo existente tem hoje
  ;; (a coluna `tipo` acabou de nascer). Este teste e' a prova de que a mig 0077 nao quebrou ninguem.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid   (template! tx ente {:chave "rito_ordinario" :estado-inicial "recebida"})
              r     (protocolar! tx ente {})
              linha (prop/buscar tx ente (:id r))]
          (is (= tid (:template-id r)) "protocolar! devolve o rito resolvido")
          (is (= "recebida" (:estado r)) "estado devolvido = estado_inicial do template")
          (is (= tid (:template-id linha)) "a LINHA guarda o elo")
          (is (= "recebida" (:estado linha))
              "Inv.4: o estado da linha vem do template, nao do literal 'protocolada'"))))))

(deftest o-elo-e-o-que-a-engine-do-eixo-C-aceita
  ;; A razao de existir da fatia: o `template_id` LIDO DA LINHA e' exatamente o que `transicionar!` pede.
  ;; Sem este elo, a borda HTTP nao tinha de onde tirar o template — por isso nunca existiu.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tid (template! tx ente {:chave "rito_ordinario" :estado-inicial "recebida"})]
          (doseq [[ch term] [["recebida" false] ["em_comissoes" false]]]
            (tram/criar-estado! tx {:id (random-uuid) :ente-id ente :template-id tid :chave ch
                                    :nome ch :terminal term}))
          (tram/criar-transicao! tx {:id (random-uuid) :ente-id ente :template-id tid
                                     :de-estado "recebida" :para-estado "em_comissoes"
                                     :gatilho "despachar" :guarda nil :ordem 1})
          (let [{pid :id} (protocolar! tx ente {})
                tid-da-linha (:template-id (prop/buscar tx ente pid))
                r (tram/transicionar! tx {:registro *registro* :ente-id ente :proposicao-id pid
                                          :template-id tid-da-linha :gatilho "despachar"
                                          :agora (LocalDate/parse "2026-03-01")})]
            (is (true? (:transicionou? r)) "a materia tramita com o rito que ela mesma guarda")
            (is (= "em_comissoes" (:estado (prop/buscar tx ente pid))))))))))

;; ---------------------------------------------------------------------------------------------------
;; A RESOLUCAO POR ESPECIE — a terca-feira de Fortaleza
;; ---------------------------------------------------------------------------------------------------

(deftest tres-ritos-tres-especies-cada-materia-no-seu-caminho
  ;; O caso que a regra ANTERIOR ('um rito ativo por Casa') tornava impossivel: com estes tres ritos
  ;; cadastrados, a Casa nao conseguia protocolar NADA. Agora cada especie acha o rito dela.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [pl  (template! tx ente {:chave "rito_pl"  :estado-inicial "recebida"  :tipo "projeto_lei"})
              req (template! tx ente {:chave "rito_req" :estado-inicial "autuada"   :tipo "requerimento"})
              moc (template! tx ente {:chave "rito_moc" :estado-inicial "conferida" :tipo "mocao"})
              r-pl  (protocolar! tx ente {:tipo "projeto_lei"})
              r-req (protocolar! tx ente {:tipo "requerimento" :tipo-requerimento "informacao"})
              r-moc (protocolar! tx ente {:tipo "mocao" :categoria-mocao "aplauso"})]
          (is (= pl (:template-id r-pl)))   (is (= "recebida"  (:estado r-pl)))
          (is (= req (:template-id r-req))) (is (= "autuada"   (:estado r-req)))
          (is (= moc (:template-id r-moc))) (is (= "conferida" (:estado r-moc)))
          (is (= 3 (count (distinct [(:template-id r-pl) (:template-id r-req) (:template-id r-moc)]))
                 ) "tres materias, tres ritos — nenhuma colisao"))))))

(deftest especie-sem-rito-proprio-cai-no-generico
  ;; A camada 3: o rito da Casa para as especies que ela nao quis particularizar.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [generico (template! tx ente {:chave "rito_geral" :estado-inicial "recebida"})
              pl       (template! tx ente {:chave "rito_pl" :estado-inicial "autuada" :tipo "projeto_lei"})
              r-moc    (protocolar! tx ente {:tipo "mocao" :categoria-mocao "aplauso"})
              r-pl     (protocolar! tx ente {:tipo "projeto_lei"})]
          (is (= generico (:template-id r-moc)) "mocao nao tem rito proprio -> generico")
          (is (= "recebida" (:estado r-moc)))
          (is (= pl (:template-id r-pl)) "o ESPECIFICO vence o generico (precedencia declarada)")
          (is (= "autuada" (:estado r-pl))))))))

(deftest sem-rito-da-especie-e-sem-generico-a-materia-nasce-sem-rito
  ;; Nao e' erro: e' o comportamento de hoje, explicito. A materia simplesmente nao tramita.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (template! tx ente {:chave "rito_pl" :estado-inicial "recebida" :tipo "projeto_lei"})
        (let [r     (protocolar! tx ente {:tipo "mocao" :categoria-mocao "aplauso"})
              linha (prop/buscar tx ente (:id r))]
          (is (nil? (:template-id r)) "nenhum rito casa a mocao, e nao ha' generico")
          (is (nil? (:template-id linha)))
          (is (= "protocolada" (:estado linha)) "estado = default da coluna (schema), nao literal do codigo")
          (is (= "protocolada" (:estado r)) "o retorno espelha a linha, nao um chute do codigo"))))))

(deftest template-explicito-vence-a-resolucao-automatica
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (template! tx ente {:chave "rito_pl" :estado-inicial "recebida" :tipo "projeto_lei"})
        (let [alvo (template! tx ente {:chave "rito_b" :estado-inicial "autuada" :tipo "mocao"})
              r    (protocolar! tx ente {:tipo "projeto_lei" :template-id alvo})]
          (is (= alvo (:template-id r)) "o explicito vence — inclusive o rito da propria especie")
          (is (= "autuada" (:estado r)) "estado = estado_inicial do template EXPLICITO"))))))

(deftest zero-templates-a-materia-nasce-sem-rito
  ;; Comportamento de HOJE, agora explicito: materia sem template simplesmente NAO TRAMITA.
  ;; O 'protocolada' aqui e' o DEFAULT DA COLUNA (schema), nao um literal de decisao no codigo.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [r     (protocolar! tx ente {})
              linha (prop/buscar tx ente (:id r))]
          (is (nil? (:template-id r)) "sem rito no tenant, a materia nasce SEM rito")
          (is (nil? (:template-id linha)))
          (is (= "protocolada" (:estado linha)) "estado = default da coluna (comportamento de hoje)")
          (is (= "protocolada" (:estado r)) "o retorno espelha a linha, nao um chute do codigo"))))))

;; ---------------------------------------------------------------------------------------------------
;; Fail-closed DENTRO da camada — e a recusa que a borda consegue TRADUZIR
;; ---------------------------------------------------------------------------------------------------

(deftest dois-ritos-da-mesma-especie-recusam-em-vez-de-escolher
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (template! tx ente {:chave "rito_pl_a" :estado-inicial "recebida" :tipo "projeto_lei"})
        (template! tx ente {:chave "rito_pl_b" :estado-inicial "autuada"  :tipo "projeto_lei"})
        (is (thrown-with-msg? Exception #"mais de um rito ativo para a especie"
              (protocolar! tx ente {:tipo "projeto_lei"}))
            "config ambigua FALHA FECHADA — o codigo nao escolhe o rito da Casa por conta")
        (is (some? (protocolar! tx ente {:tipo "mocao" :categoria-mocao "aplauso"}))
            "e a ambiguidade e' LOCAL a especie: as outras especies da Casa continuam protocolando")))))

(deftest dois-ritos-genericos-recusam
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (template! tx ente {:chave "rito_a" :estado-inicial "recebida"})
        (template! tx ente {:chave "rito_b" :estado-inicial "autuada"})
        (is (thrown-with-msg? Exception #"mais de um rito GENERICO"
              (protocolar! tx ente {:tipo "projeto_lei"})))))))

(deftest rito-especifico-unico-nao-consulta-os-genericos-ambiguos
  ;; A camada generica so' e' visitada quando a especifica devolve ZERO. Sem isso, uma Casa com o rito de
  ;; PL certinho ficaria refem de uma ambiguidade em ritos genericos que nao a governam.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (template! tx ente {:chave "gen_a" :estado-inicial "recebida"})
        (template! tx ente {:chave "gen_b" :estado-inicial "autuada"})
        (let [pl (template! tx ente {:chave "rito_pl" :estado-inicial "conferida" :tipo "projeto_lei"})
              r  (protocolar! tx ente {:tipo "projeto_lei"})]
          (is (= pl (:template-id r)))
          (is (= "conferida" (:estado r))))))))

(deftest a-recusa-carrega-tipo-no-namespace-config-para-a-borda-traduzir
  ;; SEM esta chave a excecao cai no `:else` de `interceptors/erro` e vira 500 opaco em
  ;; `POST /legislativo/proposicoes` — indistinguivel de um bug do servidor, para um problema que so' o
  ;; operador da Casa pode consertar. O mapeamento `config/* -> 409` esta' provado em interceptors-test.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (template! tx ente {:chave "rito_pl_a" :estado-inicial "recebida" :tipo "projeto_lei"})
        (template! tx ente {:chave "rito_pl_b" :estado-inicial "autuada"  :tipo "projeto_lei"})
        (let [d (try (protocolar! tx ente {:tipo "projeto_lei"}) nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= :config/rito-ambiguo-na-especie (:tipo d)))
          (is (= "projeto_lei" (:especie d)) "a especie acionavel viaja na ex-data"))
        (is (= "config" (namespace (:tipo (try (do (template! tx ente {:chave "g_a" :estado-inicial "x"})
                                                   (template! tx ente {:chave "g_b" :estado-inicial "y"})
                                                   (protocolar! tx ente {:tipo "mocao" :categoria-mocao "aplauso"}))
                                               nil
                                               (catch clojure.lang.ExceptionInfo e (ex-data e))))))
            "a ambiguidade de GENERICO tambem e' mapeavel (tag propria, mesmo namespace)")))))

(deftest recusa-por-ambiguidade-nao-queima-numero
  ;; Eixo H: a numeracao oficial e' GAPLESS. Se a resolucao do rito rodasse DEPOIS de `sequencial/proximo!`,
  ;; toda tentativa recusada abriria um buraco na numeracao da Casa.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [t1 (template! tx ente {:chave "rito_a" :estado-inicial "recebida" :tipo "projeto_lei"})]
          (is (= 1 (:sequencial (protocolar! tx ente {:tipo "projeto_lei"}))))
          (template! tx ente {:chave "rito_b" :estado-inicial "autuada" :tipo "projeto_lei"})
          (is (thrown? Exception (protocolar! tx ente {:tipo "projeto_lei"})))
          (is (= 2 (:sequencial (protocolar! tx ente {:tipo "projeto_lei" :template-id t1})))
              "a tentativa RECUSADA nao consumiu numero"))))))

;; ---------------------------------------------------------------------------------------------------
;; A VALVULA: aposentar-template! (o caminho que o produto agora tem — nao mais fixture fabricando `ativo`)
;; ---------------------------------------------------------------------------------------------------

(deftest aposentar-desambigua-o-bump-de-versao-do-rito
  ;; Versionamento de template e' por COPIA INTEGRAL (mig 0016): apos o bump, a Casa tem 2 linhas com a
  ;; MESMA especie. Sem aposentar a v1, `protocolar!` recusaria toda materia daquela especie.
  ;; As duas escritas na MESMA tx de proposito: a janela entre elas nao pode existir.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [v1 (template! tx ente {:chave "rito_v1" :estado-inicial "recebida" :tipo "projeto_lei"})]
          (is (= v1 (:template-id (protocolar! tx ente {:tipo "projeto_lei"}))) "antes do bump, a v1 rege")
          (is (true? (tram/aposentar-template! tx {:ente-id ente :id v1})) "a valvula existe e agiu")
          (let [v2 (template! tx ente {:chave "rito_v2" :estado-inicial "autuada" :tipo "projeto_lei"})
                r  (protocolar! tx ente {:tipo "projeto_lei"})]
            (is (= v2 (:template-id r)) "so' o rito ATIVO conta na resolucao automatica")
            (is (= "autuada" (:estado r)))))))))

(deftest aposentar-e-idempotente-e-nao-mente-sobre-quem-agiu
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [t (template! tx ente {:chave "rito_x" :estado-inicial "recebida"})]
          (is (true? (tram/aposentar-template! tx {:ente-id ente :id t})) "1a chamada aposentou")
          (is (false? (tram/aposentar-template! tx {:ente-id ente :id t}))
              "2a nao aposentou nada — reaplicar nao e' erro, mas tambem nao e' ato")
          (is (false? (tram/aposentar-template! tx {:ente-id ente :id (random-uuid)}))
              "id inexistente no tenant: false, nunca true")
          (is (nil? (:template-id (protocolar! tx ente {:tipo "projeto_lei"})))
              "sem rito ativo, a materia volta a nascer sem rito"))))))

(deftest template-explicito-vence-ate-o-rito-aposentado
  ;; Quem NOMEIA o template esta' declarando a intencao — importacao de acervo entra num rito aposentado
  ;; de proposito. A camada 1 nao olha `ativo`, e isto e' contrato, nao descuido.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [velho (template! tx ente {:chave "rito_v1" :estado-inicial "recebida" :tipo "projeto_lei"})]
          (tram/aposentar-template! tx {:ente-id ente :id velho})
          (template! tx ente {:chave "rito_v2" :estado-inicial "autuada" :tipo "projeto_lei"})
          (let [r (protocolar! tx ente {:tipo "projeto_lei" :template-id velho})]
            (is (= velho (:template-id r)))
            (is (= "recebida" (:estado r)))))))))

;; ---------------------------------------------------------------------------------------------------
;; O GATE DE VOCABULARIO no save do template (fail-closed na CONFIG, nao no fluxo)
;; ---------------------------------------------------------------------------------------------------

(deftest especie-desconhecida-e-recusada-no-SAVE-do-template
  ;; Um typo ('projeto-lei' com hifen) produziria um rito que nunca casa especie nenhuma — e o sintoma na
  ;; outra ponta seria SILENCIOSO (materia nascendo sem rito e' desfecho legitimo, nada apitaria).
  ;; Mesma disciplina de `criar-transicao!` com o guard: rejeita na configuracao, nao no meio do fluxo.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (thrown-with-msg? Exception #"especie desconhecida"
              (template! tx ente {:chave "rito_typo" :estado-inicial "recebida" :tipo "projeto-lei"})))
        (is (thrown-with-msg? Exception #"nao governa especie"
              (template! tx ente {:chave "rito_par" :estado-inicial "x" :sujeito "parecer"
                                  :tipo "projeto_lei"}))
            "template de parecer nao governa especie de materia")))))

(deftest o-check-do-schema-tambem-barra-especie-em-template-de-parecer
  ;; Defesa em profundidade: o gate do service e' Clojure, mas a mig 0077 nao deixa a linha incoerente
  ;; entrar nem por SQL direto (import/manutencao).
  (let [ente (random-uuid)]
    (is (thrown? Exception
          (tenancy/com-tenant* *ds* ente
            (fn [tx]
              (jdbc/execute-one! tx
                [(str "INSERT INTO legislativo.template_tramitacao "
                      "(ente_id, id, chave, versao, nome, estado_inicial, sujeito, tipo, efetivado_em) "
                      "VALUES (?, ?, 'x', 1, 'x', 'x', 'parecer', 'projeto_lei', now())")
                 ente (random-uuid)]))))
        "CHECK template_tramitacao_tipo_sujeito_ck")))

;; ---------------------------------------------------------------------------------------------------
;; Fail-closed no explicito
;; ---------------------------------------------------------------------------------------------------

(deftest template-explicito-inexistente-recusa
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (is (thrown-with-msg? Exception #"template inexistente"
              (protocolar! tx ente {:template-id (random-uuid)})))))))

(deftest template-de-sujeito-parecer-e-recusado
  ;; Anti-misconfig cross-sujeito — o espelho exato de `db/parecer/criar!`, que recusa template de
  ;; 'proposicao'. As tabelas de template sao subject-agnosticas; quem valida e' o service do sujeito.
  (let [ente (random-uuid)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx]
        (let [tp (template! tx ente {:chave "parecer_ccj" :estado-inicial "aguardando_designacao"
                                     :sujeito "parecer"})]
          (is (thrown-with-msg? Exception #"sujeito 'proposicao'"
                (protocolar! tx ente {:template-id tp}))
              "template de parecer nao rege materia (explicito)")
          (let [r (protocolar! tx ente {})]
            (is (nil? (:template-id r))
                "e o automatico tambem nao o enxerga — sujeito 'parecer' fica fora da busca")))))))

;; ---------------------------------------------------------------------------------------------------
;; O elo tem dentes no BANCO, nao so' no service
;; ---------------------------------------------------------------------------------------------------

(deftest fk-recusa-template-inexistente
  (let [ente (random-uuid) pid (atom nil)]
    (tenancy/com-tenant* *ds* ente
      (fn [tx] (reset! pid (:id (protocolar! tx ente {})))))
    (is (thrown? Exception
          (tenancy/com-tenant* *ds* ente
            (fn [tx]
              (jdbc/execute-one! tx
                ["UPDATE legislativo.proposicoes SET template_id = ? WHERE ente_id = ? AND id = ?"
                 (random-uuid) ente @pid]))))
        "FK (ente_id, template_id) -> template_tramitacao recusa rito inexistente")))

(deftest template-de-outro-tenant-nao-serve
  (let [ente-a (random-uuid) ente-b (random-uuid) tid-b (atom nil) pid (atom nil)]
    (tenancy/com-tenant* *ds* ente-b
      (fn [tx] (reset! tid-b (template! tx ente-b {:chave "rito_b" :estado-inicial "recebida"}))))
    (tenancy/com-tenant* *ds* ente-a
      (fn [tx] (reset! pid (:id (protocolar! tx ente-a {})))))
    ;; (a) o service nao ve' o template do vizinho (RLS) — de dentro do tenant A ele nao existe
    (is (thrown-with-msg? Exception #"template inexistente"
          (tenancy/com-tenant* *ds* ente-a
            (fn [tx] (protocolar! tx ente-a {:template-id @tid-b})))))
    ;; (b) e o banco recusa o mesmo elo por baixo do service (FK same-tenant, nao so' FK)
    (is (thrown? Exception
          (tenancy/com-tenant* *ds* ente-a
            (fn [tx]
              (jdbc/execute-one! tx
                ["UPDATE legislativo.proposicoes SET template_id = ? WHERE ente_id = ? AND id = ?"
                 @tid-b ente-a @pid]))))
        "o par (ente_id, template_id) e' que fecha — template do vizinho nao casa")))
