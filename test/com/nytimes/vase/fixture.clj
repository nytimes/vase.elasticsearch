(ns com.nytimes.vase.fixture
  "Elasticsearch test fixture."
  (:require [clj-test-containers.core :as test-container]))

(defonce ^{:doc "Reference to the Elasticsearch cluster."}
  container nil)

(defonce ^{:doc "Reference to the Elasticsearch client."}
  client nil)

(def container-definition
  {:image-name    "docker.elastic.co/elasticsearch/elasticsearch:7.5.2"
   :exposed-ports [9200]
   :env-vars      {"cluster.name"          "test-cluster"
                   "bootstrap.memory_lock" "true"
                   "discovery.type"        "single-node"
                   "ES_JAVA_OPTS"          "-Xms512m -Xmx512m"}})

(defn start-elasticsearch []
  (alter-var-root #'container
                  (constantly
                   (test-container/start!
                    (test-container/create container-definition)))))

(defn stop-elasticsearch []
  (alter-var-root #'container test-container/stop!))

(defn start-client []
  (alter-var-root #'client
                  (constantly
                   {:hosts [(format "http://%s:%d"
                                    (get-in container [:host])
                                    (get-in container [:mapped-ports 9200]))]})))

(defn elasticsearch-fixture [test-fn]
  (start-elasticsearch)
  (start-client)
  (test-fn)
  (stop-elasticsearch))


