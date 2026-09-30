(ns oplenario.legislativo.db.parametro-parecer
  "Parametro por Casa do PARECER JURIDICO no portal (ADR-0019 Eixo 4, mig 20260930000131): `publicar-ao-assinar?`. Falso
  (o padrao, e a ausencia de linha) = o portal so' mostra o parecer DEPOIS da deliberacao da materia (LAI art. 7 §3);
  verdadeiro = mostra assim que assinado. Uma linha por Casa (RLS); funcoes sobre a `tx` do tenant."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(set! *warn-on-reflection* true)

(defn publicar-ao-assinar?
  "O parametro da Casa; sem linha, false (so' depois da deliberacao)."
  [tx ente-id]
  (boolean (:publicar_ao_assinar
            (jdbc/execute-one! tx (sql/format {:select [:publicar_ao_assinar]
                                               :from [:legislativo.parametro_parecer_juridico]
                                               :where [:= :ente_id ente-id]})
                               {:builder-fn rs/as-unqualified-maps}))))

(defn parametros
  "{:publicar-ao-assinar bool}: o que o `admin_ente` le e altera."
  [tx ente-id]
  {:publicar-ao-assinar (publicar-ao-assinar? tx ente-id)})

(defn salvar!
  "UPSERT do parametro da Casa. Devolve os parametros ja' gravados."
  [tx ente-id {:keys [publicar-ao-assinar por]}]
  (jdbc/execute-one! tx
    (sql/format {:insert-into :legislativo.parametro_parecer_juridico
                 :values [{:ente_id ente-id :publicar_ao_assinar (boolean publicar-ao-assinar) :atualizado_por por}]
                 :on-conflict [:ente_id]
                 :do-update-set {:publicar_ao_assinar :excluded.publicar_ao_assinar
                                 :atualizado_por :excluded.atualizado_por
                                 :atualizado_em [:now]}}))
  (parametros tx ente-id))
