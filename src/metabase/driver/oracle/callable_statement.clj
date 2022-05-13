(ns metabase.driver.oracle.callable-statement
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [metabase.query-processor.context :as context]
            [metabase.query-processor.error-type :as qp.error-type]
            [metabase.query-processor.middleware.limit :as limit]
            [metabase.models.setting :refer [defsetting]]
            [metabase.driver.sql-jdbc.execute :as jdbc.execute]
            [metabase.query-processor.reducible :as qp.reducible]
            [metabase.query-processor.store :as qp.store]
            [metabase.query-processor.timezone :as qp.timezone]
            [metabase.query-processor.util :as qputil]
            [metabase.util :as u]
            [metabase.util.i18n :refer [trs tru]]
            [potemkin :as p])
  (:import [java.sql Connection ResultSet ResultSetMetaData Statement Types CallableStatement]
           [java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime]
           javax.sql.DataSource))

;(defsetting ^:private sql-jdbc-fetch-size
;            "Fetch size for result sets. We want to ensure that the jdbc ResultSet objects are not realizing the entire results
;            in memory."
;            :default 500
;            :type :integer
;            :visibility :internal)

(defn callable-statement
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
      ;(try
      ;  (when (zero? (.getFetchSize stmt))
      ;    (.setFetchSize stmt (sql-jdbc-fetch-size)))
      ;  (catch Throwable e
      ;    (log/debug e (trs "Error setting prepared statement fetch size to fetch-size"))))
      (jdbc.execute/set-parameters! driver stmt params)
      stmt
      (catch Throwable e
        (.close stmt)
        (throw e)))))

(defn callable-statement*
  ^CallableStatement [driver conn sql params canceled-chan]
  ;; if canceled-chan gets a message, cancel the PreparedStatement
  (let [^CallableStatement stmt (callable-statement driver conn sql params)]
    (a/go
      (when (a/<! canceled-chan)
        (log/debug (trs "Query canceled, calling CallableStatement.cancel()"))
        (u/ignore-exceptions
          (.cancel stmt))))
    stmt))

(defn execute-callable-statement
  [conn stmt]
  (if (.execute stmt) (do (.commit conn) true) false))

(defn execute-callable-query
  [driver sql params context respond]
  (respond { :cols [{:name "temp"}] } (qp.reducible/reducible-rows (fn [] false) (context/canceled-chan context)) )
  )
  ;((with-open [conn  (jdbc.execute/connection-with-timezone driver (qp.store/database) (qp.timezone/report-timezone-id-if-supported))
  ;             stmt (callable-statement* driver conn sql params (context/canceled-chan context))]))
  ;((respond { :cols [{:name "temp"}] } (qp.reducible/reducible-rows (fn [] false) (context/canceled-chan context)) ))
  ;(println "exit"))

;(
;  (with-open [conn          (connection-with-timezone driver (qp.store/database) (qp.timezone/report-timezone-id-if-supported))
;              stmt          (callable-statement* driver conn sql params (context/canceled-chan context))]
;      (respond { :cols [{:name "temp"}] } (qp.reducible/reducible-rows (fn [] false) (context/canceled-chan context)) ))))

;(try
;  (execute-callable-statement driver conn stmt)
;  (catch Throwable e
;    (throw (ex-info (tru "Error executing query: {0}" (ex-message e)) {:sql sql, :params params, :type qp.error-type/invalid-query} e))
;    )
;  )

