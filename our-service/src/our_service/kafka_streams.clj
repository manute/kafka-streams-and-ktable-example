(ns our-service.kafka-streams
  (:require
    [our-service.util :as k]
    [franzy.serialization.deserializers :as deserializers]
    [franzy.serialization.serializers :as serializers]
    [clojure.set :as set]
    [clojure.tools.logging :as log])
  (:gen-class)
  (:import (java.util Properties)
           (org.apache.kafka.streams StreamsConfig KafkaStreams KeyValue)
           (org.apache.kafka.common.serialization Serde Serdes Serializer)
           (org.apache.kafka.clients.consumer ConsumerConfig)
           (org.apache.kafka.streams.kstream KStreamBuilder)
           (org.apache.kafka.streams.state QueryableStoreTypes)))


;;;
;;; Serialization stuff
;;;

(deftype NotSerializeNil [edn-serializer]
  Serializer
  (configure [_ configs isKey] (.configure edn-serializer configs isKey))
  (serialize [_ topic data]
    (when data (.serialize edn-serializer topic data)))
  (close [_] (.close edn-serializer)))

;; Can be global as they are thread-safe
(def serializer (NotSerializeNil. (serializers/edn-serializer)))
(def deserializer (deserializers/edn-deserializer))

(deftype EdnSerde []
  Serde
  (configure [this map b])
  (close [this])
  (serializer [this]
    serializer)
  (deserializer [this]
    deserializer))

;;;
;;; Application
;;;

(defn kafka-config []
  (doto
    (Properties.)
    (.put StreamsConfig/APPLICATION_ID_CONFIG "example-consumer")
    (.put StreamsConfig/BOOTSTRAP_SERVERS_CONFIG "kafka1:9092")
    (.put StreamsConfig/ZOOKEEPER_CONNECT_CONFIG "zoo1:2181")
    (.put StreamsConfig/CACHE_MAX_BYTES_BUFFERING_CONFIG 0)
    (.put StreamsConfig/COMMIT_INTERVAL_MS_CONFIG 100000)
    (.put StreamsConfig/KEY_SERDE_CLASS_CONFIG (class (Serdes/String)))
    (.put StreamsConfig/VALUE_SERDE_CLASS_CONFIG EdnSerde)
    (.put ConsumerConfig/AUTO_OFFSET_RESET_CONFIG "earliest")))

;;;
;;; Create topology, but do not start it
;;;
(defn create-kafka-stream-topology []
  (let [builder (KStreamBuilder.)
        us-share-holders
        (->
          (.table builder "share-holders" "share-holder-store")
          (.filter (k/pred [key position]
                     (log/info "Filtering" key position)
                     (= "NASDAQ" (:exchange position))))
          (.groupBy (k/kv-mapper [key position]
                      (log/info "Grouping" key position)
                      (KeyValue/pair (:client position)
                                     #{(:id position)})))
          (.reduce (k/reducer [value1 value2]
                     (log/info "adding" value1 value2)
                     (set/union value1 value2))
                   (k/reducer [value1 value2]
                     (log/info "removing" value1 value2)
                     (let [result (set/difference value1 value2)]
                       (when-not (empty? result)
                         result)))
                   "us-share-holders"))]
    [builder us-share-holders]))

(defn get-all-in-local-store [kafka-streams]
  (fn []
    (with-open [all (.all (.store kafka-streams "us-share-holders" (QueryableStoreTypes/keyValueStore)))]
      (doall
        (map (fn [x] {:key   (.key x)
                      :value (.value x)})
             (iterator-seq all))))))

(defn start-kafka-streams []
  (let [[builder us-share-holders] (create-kafka-stream-topology)
        kafka-streams (KafkaStreams. builder (kafka-config))]
    (.print us-share-holders)
    (.start kafka-streams)
    [kafka-streams (get-all-in-local-store kafka-streams)]))