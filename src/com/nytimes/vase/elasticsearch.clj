(ns com.nytimes.vase.elasticsearch
  (:require [clojure.core.match :refer [match]]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.cognitect.vase.actions :as actions]
            [com.cognitect.vase.fern :as vf]
            [fern :as f]
            [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.helpers :as interceptor]
            [io.pedestal.log :as log]
            [qbits.spandex :as es])
  (:import clojure.lang.ExceptionInfo
           java.io.Writer
           [org.elasticsearch.client Node RestClient]))

(s/def :vase.elasticsearch.index/name     string?)
(s/def :vase.elasticsearch.index/settings (s/or :file-name string? :edn map?))

(defn hosts
  [^RestClient client]
  (into [] (for [^Node node (.getNodes client)]
             (str \" (.getHost node) \"))))

(defmethod print-method RestClient
  [^RestClient client ^Writer writer]
  (.write writer (format "org.elasticsearch.client.RestClient[%s]"
                         (str/join "," (hosts client)))))


(defn error-response
  "Extract the error response from an Elasticsearch exception."
  [^clojure.lang.ExceptionInfo ex]
  (-> ex ex-data))

(defn error-status
  [exception]
  (let [data (ex-data exception)]
    (get data :status 500)))

(defn error-body
  [exception]
  (let [data (ex-data exception)]
    (get-in data [:body :error] data)))

(def default-type
  "This is the default document type in Elasticsearch."
  :_doc)

;; ----------------------------------------------------------------------
;; Connect

(defn ready?
  [client]
  (log/info :msg "Attempting to connect to Elasticsearch." :client client)
  (try
    (es/request client
                {:method       :get
                 :url          "_cluster/health"
                 :query-string {"wait_for_status" "yellow"}})
    true
    (catch Throwable _ false)))

(defmethod f/literal 'vase.elasticsearch/connect
  [_ opts]
  (let [client (es/client opts)]
    (while (not (ready? client))
      (Thread/sleep 1000))
    (log/info :msg "Adding Elasticsearch client.")
    (interceptor/before
     ::connect
     (fn [context]
       (assoc context ::client client)))))

;; ----------------------------------------------------------------------
;; Generic Elasticsearch Request

(defn merged-parameters
  "Create a map of all possible parameters by extracting and then
  merging them from the request object."
  [{:keys [query-params path-params params json-params edn-params]}]
  {:post [(map? %)]}
  (merge (if (empty? path-params) {} (actions/decode-map path-params))
         params
         json-params
         edn-params
         query-params))

(defn request-expr
  "Build a function which queries Elasticsearch and returns the response."
  [{:keys [params url method body]}]
  (assert (some? url) "URL must be a string or a vector of URL components")
  (assert (some? method) "Method must be a valid HTTP method such as :get or :post")
  `(fn [{~'request :request :as ~'context}]
     (let [client#                (::client ~'context)
           ~(actions/bind params) (merged-parameters ~'request)
           request#               {:url    ~url
                                   :method ~method
                                   :body   (or ~body (:body ~'request))}]
       (assoc ~'context :response (es/request client# request#)))))

(defrecord ElasticsearchRequestAction [name params url method body]
  i/IntoInterceptor
  (-interceptor [_]
    (actions/dynamic-interceptor
     name
     {:enter
      (request-expr {:url    url
                     :method method
                     :params params
                     :body   body})

      :error
      (fn [context exception]
        (log/error :msg "Elasticsearch request failed"
                   :status (error-status exception)
                   :error (error-body exception))
        (assoc context :response (error-response exception)))})))

(defmethod f/literal 'vase.elasticsearch/request [_ d] (map->ElasticsearchRequestAction (vf/with-name d)))

;; ----------------------------------------------------------------------
;; Indices API

(defn index-exists?
  "Return true if name index exists."
  [client index]
  (= 200 (try
           (:status (es/request client {:url index :method :head}))
           (catch Exception e
             (:status (ex-data e))))))

(defn content-type
  "Return settings file content type."
  [file-name]
  {:pre [(some? file-name) (not (str/blank? file-name))]}
  (if (re-find #"\.ya?ml$" file-name)
    "application/yaml"
    "application/json"))

(s/def :vase.elasticsearch/create
  (s/keys :req-un [:vase.elasticsearch.index/name
                   :vase.elasticsearch.index/settings]))


;; Create:

(defrecord CreateIndexAction [name index settings]
  i/IntoInterceptor
  (-interceptor [_]
    (assert (and (string? index) (not (str/blank? index)))
            "Index must be a non-empty string")
    (assert (or (map? settings)
                (and (string? settings)
                     (or (str/ends-with? settings ".json")
                         (str/ends-with? settings ".yaml")
                         (str/ends-with? settings ".yml"))))
            "Settings must be a map or a file ending with .json, .yaml, or .yml")
    (actions/dynamic-interceptor
     name
     {:enter
      (fn [{:keys [::client] :as context}]
        (when (nil? client)
          (throw (ex-info (str "No Elasticsearch client in the context. "
                               "Is the vase.elasticsearch/connect interceptor in the interceptor chain?")
                          {})))

        (if (index-exists? client index)
          (do (log/info :msg "Index already exists, doing nothing") context)
          (let [request  {:url     index
                          :method  :put
                          :headers {"content-type" (if (string? settings)
                                                     (content-type settings) "application/json")
                                    "accept"       "application/json"}
                          :body    (if (string? settings)
                                     (es/raw (slurp (io/file settings)))
                                     settings)}
                response (es/request client request)
                status   (:status response)
                body     (:body response)]
            (log/info :msg "Index created" :status status :response body)
            (assoc context :status status :response body))))

      :error
      (fn [context exception]
        (log/error :msg "Failed to create index. See stracktrace for Elasticsearch error."
                   :status (error-status exception)
                   :error (error-body exception))
        (throw exception))})))

;; Literals:

(defmethod f/literal 'vase.elasticsearch/create [_ d] (map->CreateIndexAction d))

;; ----------------------------------------------------------------------
;; Document API

;; Get:

(defrecord GetDocumentAction [name index type id-path]
  i/IntoInterceptor
  (-interceptor [_]
    (assert (vector? id-path) "id-path must be a vector into the context")
    (actions/dynamic-interceptor
     name
     {:enter
      (fn [{:keys [::client] :as context}]
        (let [type (or type default-type)
              id   (str (get-in context id-path))
              req  {:url    [index type id]
                    :method :get}]
          (log/info :msg "GET document" :index index :type type :id id)
          (assoc context :response (es/request client req))))

      :error
      (fn [context exception]
        (log/error :msg "Failed to GET document"
                   :id (get-in context id-path)
                   :status (error-status exception)
                   :error (error-body exception))
        (assoc context :response (error-response exception)))})))

;; Put:

(defrecord PutDocumentAction [name index type id-path body-path]
  i/IntoInterceptor
  (-interceptor [_]
    (actions/dynamic-interceptor
     name
     {:enter
      (fn [{:keys [::client] :as context}]
        (let [type (or type default-type)
              id   (str (get-in context id-path))
              body (get-in context body-path)
              req  {:url    [index type id]
                    :body   body
                    :method :put}]
          (log/info :msg "PUT document" :index index :type type :id id)
          (assoc context :response (es/request client req))))

      :error
      (fn [context exception]
        (log/error :msg "Failed to PUT document"
                   :id (get-in context id-path)
                   :status (error-status exception)
                   :error (error-body exception))
        (assoc context :response (error-response exception)))})))

;; Delete:


(defrecord DeleteDocumentAction [name index type id-path]
  i/IntoInterceptor
  (-interceptor [_]
    (actions/dynamic-interceptor
     name
     {:enter
      (fn [{:keys [::client] :as context}]
        (let [type     (or type default-type)
              id       (str (get-in context id-path))
              request  {:url    [index type id]
                        :method :delete}]
          (log/info :msg "DELETE document" :index index :type type :id id)
          (assoc context :response (es/request client request))))

      :error
      (fn [context exception]
        (log/error :msg "Failed to DELETE document"
                   :id (get-in context id-path)
                   :status (error-status exception)
                   :error (error-body exception)
                   :exception exception)
        (assoc context :response (error-response exception)))})))


;; Literals:

(defmethod f/literal 'vase.elasticsearch/get-document [_ d] (map->GetDocumentAction (vf/with-name d)))
(defmethod f/literal 'vase.elasticsearch/put-document [_ d] (map->PutDocumentAction (vf/with-name d)))
(defmethod f/literal 'vase.elasticsearch/delete-document [_ d] (map->DeleteDocumentAction (vf/with-name d)))

;; ----------------------------------------------------------------------
;; Search API

;; Search:

(defrecord SearchAction [name index params method body]
  i/IntoInterceptor
  (-interceptor [_]
    (actions/dynamic-interceptor
     name
     {:enter
      (request-expr {:url    [index :_search]
                     :method (or method :get)
                     :params params
                     :body   body})

      :error
      (fn [context exception]
        (log/error :msg "Exception during search"
                   :status (error-status exception)
                   :error (error-body exception)
                   :exception exception)
        (assoc context :response (error-response exception)))})))

;; Count:

(defrecord CountAction [name index params method body]
  i/IntoInterceptor
  (-interceptor [_]
    (actions/dynamic-interceptor
     name
     {:enter
      (request-expr {:url    [index :_count]
                     :method (or method :get)
                     :params params
                     :body   body})

      :error
      (fn [context exception]
        (log/error :msg "Exception during count"
                   :status (error-status exception)
                   :error (error-body exception)
                   :exception exception)
        (assoc context :response (error-response exception)))})))

;; Literals:

(defmethod f/literal 'vase.elasticsearch/search [_ d] (map->SearchAction (vf/with-name d)))
(defmethod f/literal 'vase.elasticsearch/count [_ d] (map->CountAction (vf/with-name d)))









(comment

  (def printerceptor
    {:name ::printerceptor
     :enter
     (fn [ctx]
       (prn ctx)
       ctx)})

  (clojure.pprint/pprint
   (request-expr '{:url    [:_search]
                   :method :get
                   :params [q]
                   :body   {:query {:multi_match {:query  (or q "")
                                                  :fields ["title" "overview"]}}}}))


  (require 'qbits.spandex.spec)
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument (stest/instrumentable-syms 'qbits.spandex.spec))
  (def c (es/client
          {:hosts       ["http://localhost:9201"]
           :http-client {:basic-auth {:user "elastic" :password ""}}}))


  (require '[io.pedestal.interceptor.chain :as chain])
  (chain/execute {} (map i/interceptor
                         [(f/literal 'vase.elasticsearch/connect {:hosts ["http://localhost:9200"]})
                          (f/literal 'vase.elasticsearch/delete-document {:index "test" :type "_doc" :id-path [:a]})
                          #_(f/literal 'vase.elasticsearch/create {:name "tmdb2" :settings "dev/settings.json"})]))

  ;; Testing a running vase system
  (require '[com.cognitect.vase.main])
  (require '[io.pedestal.http :as http])
  (def server (com.cognitect.vase.main/-main  "dev/tmdb.fern"))

  )
