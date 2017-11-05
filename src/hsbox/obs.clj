(ns hsbox.obs
  (:require [brainoutsource.clojure-websocket :as ws]
            [clojure.data.json :as json]))

(def hsbox.obs/socket nil)

(defn connect []
  (if (nil? socket)
    (def hsbox.obs/socket
      (ws/connect "ws://localhost:4444/"
        :on-message #(prn 'received %)))))

(defn start-recording []
  (connect)
  (ws/send-msg socket (json/write-str {:message-id 1
                                       :request-type "StartRecording"})))

(defn stop-recording []
  (connect)
  (ws/send-msg socket (json/write-str {:message-id 1
                                       :request-type "StopRecording"})))

(defn close []
  (ws/close socket 1000)
  (def hsbox.obs/socket nil))