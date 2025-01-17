(ns metabase.sync.analyze
  "Logic responsible for doing deep 'analysis' of the data inside a database.
   This is significantly more expensive than the basic sync-metadata step, and involves things
   like running MBQL queries and fetching values to do things like determine Table row counts
   and infer field semantic types."
  (:require [clojure.tools.logging :as log]
            [metabase.models.field :refer [Field]]
            [metabase.sync.analyze.classify :as classify]
            [metabase.sync.analyze.fingerprint :as fingerprint]
            [metabase.sync.interface :as i]
            [metabase.sync.util :as sync-util]
            [metabase.util :as u]
            [metabase.util.i18n :refer [trs]]
            [schema.core :as s]
            [toucan.db :as db]))

;; How does analysis decide which Fields should get analyzed?
;;
;; Good question. There are two situations in which Fields should get analyzed:
;;
;; *  Whenever a new Field is first detected, *or*
;; *  When the fingerprinters are updated in such a way that this Field (based on its base type) ought to be
;; *  re-fingerprinted
;;
;; So how do we check all that?
;;
;; 1.  We keep track of which base types are affected by new fingerprint versions. See the discussion in
;;     `metabase.sync.interface` for more details.
;;
;; 2.  FINGERPRINTING
;;
;;     2a. When running fingerprinting, we calculate a fairly sophisticated SQL query to only fetch Fields that
;;         need to be re-fingerprinted based on type info and their current fingerprint version
;;
;;     2b. All of these fields get updated fingerprints and marked with the newest version. We also set
;;         `last_analyzed` to `nil` so we know we need to re-run classification for them
;;
;; 3.  CLASSIFICATION
;;
;;     All Fields that have the latest fingerprint version but a `nil` `last_analyzed` time need to be re-classified.
;;     Classification takes place for these Fields and semantic types and the like are updated as needed.
;;
;; 4.  MARKING FIELDS AS RECENTLY ANALYZED
;;
;;     Once all of the above is done, we update the `last_analyzed` timestamp for all the Fields that got
;;     re-fingerprinted and re-classified.
;;
;; So what happens during the next analysis?
;;
;; During the next analysis phase, Fields whose fingerprint is up-to-date will be skipped. However, if a new
;; fingerprint version is introduced, Fields that need it will be upgraded to it. We'll still only reclassify the
;; newly re-fingerprinted Fields, because we'll know to skip the ones from last time since their value of
;; `last_analyzed` is not `nil`.

(s/defn ^:private update-last-analyzed!
  [tables :- [i/TableInstance]]
  (when-let [ids (seq (map u/the-id tables))]
    ;; The WHERE portion of this query should match up with that of `classify/fields-to-classify`
    (db/update-where! Field {:table_id            [:in ids]
                             :fingerprint_version i/latest-fingerprint-version
                             :last_analyzed       nil}
      :last_analyzed :%now)))

(s/defn ^:private update-fields-last-analyzed!
  "Update the `last_analyzed` date for all the recently re-fingerprinted/re-classified Fields in TABLE."
  [table :- i/TableInstance]
  (update-last-analyzed! [table]))

(s/defn ^:private update-fields-last-analyzed-for-db!
  "Update the `last_analyzed` date for all the recently re-fingerprinted/re-classified Fields in TABLE."
  [_database :- i/DatabaseInstance
   tables :- [i/TableInstance]]
  ;; The WHERE portion of this query should match up with that of `classify/fields-to-classify`
  (update-last-analyzed! tables))

(s/defn analyze-table!
  "Perform in-depth analysis for a `table`."
  [table :- i/TableInstance]
  (fingerprint/fingerprint-fields! table)
  (classify/classify-fields! table)
  (classify/classify-table! table)
  (update-fields-last-analyzed! table))

(defn- maybe-log-progress [progress-bar-fn]
  (fn [step table]
    (let [progress-bar-result (progress-bar-fn)]
      (when progress-bar-result
        (log/info (u/format-color 'blue "%s Analyzed %s %s" step progress-bar-result (sync-util/name-for-logging table)))))))

(defn- fingerprint-fields-summary [{:keys [fingerprints-attempted updated-fingerprints no-data-fingerprints failed-fingerprints]}]
  (trs "Fingerprint updates attempted {0}, updated {1}, no data found {2}, failed {3}"
       fingerprints-attempted updated-fingerprints no-data-fingerprints failed-fingerprints))

(defn- classify-fields-summary [{:keys [fields-classified fields-failed]}]
  (trs "Total number of fields classified {0}, {1} failed"
       fields-classified fields-failed))

(defn- classify-tables-summary [{:keys [total-tables tables-classified]}]
  (trs "Total number of tables classified {0}, {1} updated"
       total-tables tables-classified))

(defn ^:private make-analyze-steps [tables log-fn]
  [(sync-util/create-sync-step "fingerprint-fields"
                               #(fingerprint/fingerprint-fields-for-db! % tables log-fn)
                               fingerprint-fields-summary)
   (sync-util/create-sync-step "classify-fields"
                               #(classify/classify-fields-for-db! % tables log-fn)
                               classify-fields-summary)
   (sync-util/create-sync-step "classify-tables"
                               #(classify/classify-tables-for-db! % tables log-fn)
                               classify-tables-summary)])

(s/defn analyze-db!
  "Perform in-depth analysis on the data for all Tables in a given `database`. This is dependent on what each database
  driver supports, but includes things like cardinality testing and table row counting. This also updates the
  `:last_analyzed` value for each affected Field."
  [database :- i/DatabaseInstance]
        ( (log/info (u/format-color 'green (str "[no-sync-fix] deleted fn analyze-db!"))) ))
  ;(sync-util/sync-operation :analyze database (format "Analyze data for %s" (sync-util/name-for-logging database))
  ;  (let [tables (sync-util/db->sync-tables database)]
  ;    (sync-util/with-emoji-progress-bar [emoji-progress-bar (inc (* 3 (count tables)))]
  ;      (u/prog1 (sync-util/run-sync-operation "analyze" database (make-analyze-steps tables (maybe-log-progress emoji-progress-bar)))
  ;        (update-fields-last-analyzed-for-db! database tables))))))

(s/defn refingerprint-db!
  "Refingerprint a subset of tables in a given `database`. This will re-fingerprint tables up to a threshold amount of
  [[fingerprint/max-refingerprint-field-count]]."
  [database :- i/DatabaseInstance]
        ( (log/info (u/format-color 'green (str "[no-sync-fix] deleted fn refingerprint-db!"))) ))
  ;(sync-util/sync-operation :refingerprint database (format "Refingerprinting tables for %s" (sync-util/name-for-logging database))
  ;  (let [tables (sync-util/db->sync-tables database)
  ;        log-fn (fn [step table]
  ;                 (log/info (u/format-color 'blue "%s Analyzed %s" step (sync-util/name-for-logging table))))]
  ;    (sync-util/run-sync-operation "refingerprint database"
  ;                                  database
  ;                                  [(sync-util/create-sync-step "refingerprinting fields"
  ;                                                               #(fingerprint/refingerprint-fields-for-db! % tables log-fn)
  ;                                                              fingerprint-fields-summary)]))))
