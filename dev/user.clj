(ns user
  (:require [fern :as f]
            [qbits.spandex :as es]
            [com.nytimes.vase.elasticsearch :as vase.es]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.pedestal.interceptor :refer [interceptor]]
            [clj-http.client :as http]))

(set! *unchecked-math* false)

(defn exact-string
  [string]
  (str "|" string "|"))

(defn prepare-asset
  [asset]
  (let [asset (-> asset
                  (assoc :names_exact_match (into (->> asset :cast (map :name) (map exact-string))
                                                  (->> asset :directors (map :name) (map exact-string))))
                  (assoc :title_exact_match (exact-string (:title asset)))
                  (update :release_date #(when-not (str/blank? %) %)))]
    (into {} (remove (comp nil? second) asset))))

(defn index-assets []
  (let [es (es/client)]
    (doseq [movie (json/parse-stream (io/reader (io/file "dev/tmdb.json")) true)]
      (when-let [id (:id movie)]
        (println (:title movie))
        (http/put (str "http://localhost:8080/doc/" id)
                  {:body (json/encode (prepare-asset movie))
                   :content-type :json})))))

(defn -main []
  (println "Preparing to indsex assets")
  (index-assets))
