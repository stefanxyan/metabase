(ns metabase.query-processor.middleware.cache-backend.db
  (:require [clojure.tools.logging :as log]
            [metabase.models
             [interface :as models]
             [query-cache :refer [QueryCache]]]
            [metabase.public-settings :as public-settings]
            [metabase.query-processor.middleware.cache-backend.interface :as i]
            [metabase.util :as u]
            [metabase.util.date :as du]
            [toucan.db :as db]))

(defn- cached-results
  "Return cached results for QUERY-HASH if they exist and are newer than MAX-AGE-SECONDS."
  [query-hash max-age-seconds]
  (when-let [{:keys [results updated_at]} (db/select-one [QueryCache :results :updated_at]
                                            :query_hash query-hash
                                            :updated_at [:>= (du/->Timestamp (- (System/currentTimeMillis)
                                                                                (* 1000 max-age-seconds)))])]
    (assoc results :updated_at updated_at)))

(defn- purge-old-cache-entries!
  "Delete any cache entries that are older than the global max age `max-cache-entry-age-seconds` (currently 3 months)."
  []
  (db/simple-delete! QueryCache
    :updated_at [:<= (du/->Timestamp (- (System/currentTimeMillis)
                                        (* 1000 (public-settings/query-caching-max-ttl))))]))


(defn- results-below-max-threshold? [compressed-result-bytes]
  (let [max-bytes (* (public-settings/query-caching-max-kb) 1024)]
    (> max-bytes (alength compressed-result-bytes))))

(defn- save-results!
  "Save the RESULTS of query with QUERY-HASH, updating an existing QueryCache entry
  if one already exists, otherwise creating a new entry."
  [query-hash results]
  ;; Explicitly compressing the results here rather than having Toucan compress it automatically. This allows us to
  ;; get the size of the compressed output to decide whether or not to store it.
  (let [compressed-results (models/compress results)]
    (if (results-below-max-threshold? compressed-results)
      (do
        (purge-old-cache-entries!)
        (or (db/update-where! QueryCache {:query_hash query-hash}
              :updated_at (du/new-sql-timestamp)
              :results    compressed-results)
            (db/insert! QueryCache
              :query_hash query-hash
              :results    compressed-results)))
      (log/info "Results are too large to cache." (u/emoji "😫"))))
  :ok)

(def instance
  "Implementation of `IQueryProcessorCacheBackend` that uses the database for caching results."
  (reify i/IQueryProcessorCacheBackend
    (cached-results [_ query-hash max-age-seconds] (cached-results query-hash max-age-seconds))
    (save-results!  [_ query-hash results]         (save-results! query-hash results))))
