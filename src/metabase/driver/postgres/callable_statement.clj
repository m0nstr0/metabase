(ns metabase.driver.postgres.callable-statement
  (:require [clojure.core.async :as a]
            [clojure.tools.logging :as log]
            [metabase.query-processor.context :as context]
            [metabase.query-processor.error-type :as qp.error-type]
            [metabase.driver.sql-jdbc.execute :as jdbc.execute]
            [metabase.query-processor.reducible :as qp.reducible]
            [metabase.query-processor.store :as qp.store]
            [metabase.query-processor.timezone :as qp.timezone]
            [metabase.util :as u]
            [metabase.util.i18n :refer [trs tru]])
  (:import [java.sql Connection ResultSet CallableStatement]))

(defn- callable-statement
  [driver ^Connection conn ^String sql params]
  (let [stmt (.prepareCall conn sql
                           ResultSet/TYPE_FORWARD_ONLY
                           ResultSet/CONCUR_READ_ONLY
                           ResultSet/CLOSE_CURSORS_AT_COMMIT)]
    (try
      (try
        (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
        (catch Throwable e
          (log/debug e (trs "Error setting prepared statement fetch direction to FETCH_FORWARD"))))
      (jdbc.execute/set-parameters! driver stmt params)
      stmt
      (catch Throwable e
        (.close stmt)
        (throw e)))))

(defn- callable-statement*
  ^CallableStatement [driver conn sql params canceled-chan]
  ;; if canceled-chan gets a message, cancel the PreparedStatement
  (let [^CallableStatement stmt (callable-statement driver conn sql params)]
    (a/go
      (when (a/<! canceled-chan)
        (log/debug (trs "Query canceled, calling CallableStatement.cancel()"))
        (u/ignore-exceptions
          (.cancel stmt))))
    stmt))

(defn- execute-callable-statement
  [conn ^CallableStatement stmt]
  (.execute stmt)
  (.commit conn))

(defn execute-callable-query
  [driver sql params context respond]
  (let [conn  (jdbc.execute/connection-with-timezone driver (qp.store/database) (qp.timezone/report-timezone-id-if-supported))
        stmt  (callable-statement* driver conn sql params (context/canceled-chan context))]
    (try
      (try
        (execute-callable-statement conn stmt)
           (catch Throwable e
             (.rollback conn)
             (throw (ex-info (tru "Error executing query: {0}" (ex-message e)) {:sql sql, :params params, :type qp.error-type/invalid-query} e))))
      (finally
        (.close stmt)
        (.close conn)
        ))
    )
    (respond { :cols [{:name "temp"}] } (qp.reducible/reducible-rows (fn row-thunk* [] false) (context/canceled-chan context))))
