(ns com.nytimes.vase.elasticsearch-test
  (:require [clojure.java.io :as io]
            [clojure.test :as test :refer [deftest is testing]]
            [com.nytimes.vase.elasticsearch :as vase.es]
            [fern :as f]
            [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as chain]
            [com.nytimes.vase.fixture :refer [elasticsearch-fixture client]]
            [qbits.spandex :as es]))

(def index
  (str (gensym "test-index-")))

(defn context-execute
  "Execute the interceptor chain with the ES client already inject into the
  context."
  ([interceptors]
   (context-execute {} interceptors))
  ([extra interceptors]
   (chain/execute (merge {::vase.es/client (es/client client)} extra)
                  (map i/interceptor interceptors))))

(defn absolute-path [path]
  (.getAbsolutePath (io/file path)))

(defn ok? [context]
  (let [status (-> context :response :status)]
    (<= 200 status 299)))

(defn response
  "Extract the response from the interceptor context."
  [context]
  (-> context :response (dissoc :hosts)))

;; ----------------------------------------------------------------------
;; Tests

(test/use-fixtures :once elasticsearch-fixture)

(deftest connect
  (is (i/interceptor? (f/literal 'vase.elasticsearch/connect client))))

(deftest content-type
  (is (= "application/json" (vase.es/content-type "test.json")))
  (is (= "application/yaml" (vase.es/content-type "test.yaml")))
  (is (= "application/yaml" (vase.es/content-type "test.yml"))))

(deftest create
  (testing "json"
    (is (= {:acknowledged true, :shards_acknowledged true, :index "json-test"}
           (:response (context-execute
                       [(f/literal 'vase.elasticsearch/create
                                   {:index     "json-test"
                                    :settings (.getFile (io/resource "com/nytimes/vase/settings.json"))})])))))

  (testing "yaml"
    (is (= {:acknowledged true, :shards_acknowledged true, :index "yaml-test"}
           (:response (context-execute
                       [(f/literal 'vase.elasticsearch/create
                                   {:index     "yaml-test"
                                    :settings (.getFile (io/resource "com/nytimes/vase/settings.yaml"))})])))))

  (testing "direct"
    (is (= {:acknowledged true, :shards_acknowledged true, :index "direct-test"}
           (:response (context-execute
                       [(f/literal 'vase.elasticsearch/create
                                   {:index     "direct-test"
                                    :settings {:settings
                                               {:index
                                                {:number_of_shards   1
                                                 :number_of_replicas 0}}
                                               :mappings
                                               {:properties
                                                {:foo {:type :keyword}}}}})]))))))


(deftest CRUD
  (testing "create-index"
    (context-execute
     [(f/literal 'vase.elasticsearch/create
                 {:index    index
                  :settings {:settings
                             {:index
                              {:number_of_shards   1
                               :number_of_replicas 0}}
                             :mappings
                             {:properties
                              {:foo {:type :keyword}}}}})]))

  (testing "put-document"
    (let [documents [{:id 1, :body {:foo "bar"}},
                     {:id 2, :body {:foo "baz"}},
                     {:id 3, :body {:foo "quux"}}]]
      (doseq [document documents]
        (testing (str document)
          (is (ok? (context-execute
                    document
                    [(f/literal 'vase.elasticsearch/put-document
                                {:name      :crud/put-document
                                 :index     index
                                 :id-path   [:id]
                                 :body-path [:body]})]))))))
    ;; we do this to make sure that the documents we just indexed are
    ;; visible to the following operations.
    (Thread/sleep 5000))
  
  (testing "get-document"
    (is (= {:_id           "1"
            :_index        index
            :_primary_term 1
            :_seq_no       0
            :_source       {:foo "bar"}
            :_type         "_doc"
            :_version      1
            :found         true}
           (:body
            (response
             (context-execute
              {:request {:path-params {:id 1}}}
              [(f/literal 'vase.elasticsearch/get-document
                          {:name    :crud/get-document
                           :index   index
                           :id-path [:request :path-params :id]})]))))))

  (testing "update-document"
    (let [documents [{:id 1, :body {:foo "bar1"}},
                     {:id 2, :body {:foo "baz1"}},
                     {:id 3, :body {:foo "quux1"}}]]
      (doseq [document documents]
        (testing document
          (is (ok? (context-execute
                    document
                    [(f/literal 'vase.elasticsearch/put-document
                                {:name      :crud/update-document
                                 :index     index
                                 :id-path   [:id]
                                 :body-path [:body]})])))))))

  (testing "search"
    (is (= [{:_id "1", :_index index, :_score 1.0, :_source {:foo "bar"}, :_type "_doc"}
            {:_id "2", :_index index, :_score 1.0, :_source {:foo "baz"}, :_type "_doc"}
            {:_id "3", :_index index, :_score 1.0, :_source {:foo "quux"}, :_type "_doc"}]
           (get-in
            (response
             (context-execute
              [(f/literal 'vase.elasticsearch/search
                          {:name  :search/search
                           :index index
                           :body  {:query {:match_all {}}}})]))
            [:body :hits :hits]))))

  (testing "count"
    (is (= {:_shards {:failed 0, :skipped 0, :successful 1, :total 1}, :count 3}
           (:body
            (response
             (context-execute
              [(f/literal 'vase.elasticsearch/count
                          {:name  :search/count
                           :index index
                           :body  {:query {:match_all {}}}})]))))))

  (testing "bad query"
    (is (=  {:body {:error  {:col        19,
                             :line       1,
                             :reason     "[terms] query malformed, no start_object after query name",
                             :root_cause [{:col    19,
                                           :line   1,
                                           :reason "[terms] query malformed, no start_object after query name",
                                           :type   "parsing_exception"}],
                             :type       "parsing_exception"},
                    :status 400},
             :exception-type :clojure.lang.ExceptionInfo,
             :interceptor :search/count,
             :stage       :enter,
             :status      400,
             :type        :qbits.spandex/response-exception}
           (select-keys
            (response
             (context-execute
              [(f/literal 'vase.elasticsearch/count
                          {:name  :search/count
                           :index index
                           :body  {:query {:terms ["baz1" "quux1"]}}})]))
            [:body :exception-type :type :status :stage :interceptor]))))

  (testing "bad request"
    (is (= {:body           {:error  (str "Incorrect HTTP method for uri [/"
                                          index
                                          "/fake-type] and method [GET], allowed: [POST]")
                             :status 405}
            :exception-type :clojure.lang.ExceptionInfo
            :interceptor    :crud/flush
            :stage          :enter
            :status         405
            :type           :qbits.spandex/response-exception}
           (select-keys 
            (response
             (context-execute
              [(f/literal 'vase.elasticsearch/request
                          {:name   :crud/flush
                           :method :get
                           :url    [index :fake-type]})]))
            [:body :exception-type :type :status :stage :interceptor]))))

  (testing "delete-document"
    (let [documents [{:id 1}, {:id 2}, {:id 3}]]
      (doseq [document documents]
        (testing document
          (is (ok? (context-execute
                    document
                    [(f/literal 'vase.elasticsearch/delete-document
                                {:name    :crud/delete-document
                                 :index   index
                                 :id-path [:id]})]))))))))
