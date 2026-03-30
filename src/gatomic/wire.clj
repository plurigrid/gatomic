(ns gatomic.wire
  "JSON-RPC 2.0 TCP bridge for receiving WLC reservoir ticks.

   Wire format: 4-byte big-endian length prefix + JSON payload.
   Matches zig-syrup message_frame.zig and Nashator protocol.

   Listens on :9998 (one below Nashator's :9999) for wlc/tick
   notifications from the WLC reservoir, converts them to gorj
   trit-ticks, and optionally transacts into gatomic."
  (:require [clojure.data.json :as json]
            [gatomic.gorj :as gorj])
  (:import [java.net ServerSocket Socket]
           [java.io DataInputStream DataOutputStream]))

(defn read-frame
  "Read a 4-byte BE length-prefixed JSON frame. Returns parsed map or nil on EOF."
  [^DataInputStream dis]
  (try
    (let [len (.readInt dis)
          buf (byte-array len)]
      (.readFully dis buf)
      (json/read-str (String. buf "UTF-8") :key-fn keyword))
    (catch java.io.EOFException _ nil)))

(defn write-frame
  "Write a 4-byte BE length-prefixed JSON frame."
  [^DataOutputStream dos msg]
  (let [bs (.getBytes (json/write-str msg) "UTF-8")]
    (.writeInt dos (count bs))
    (.write dos bs)
    (.flush dos)))

(defn handle-notification
  "Process a JSON-RPC 2.0 notification. Returns a trit-tick or nil."
  [msg prev-trit]
  (when (= (:method msg) "wlc/tick")
    (gorj/wlc-tick->tick (:params msg) {:prev-trit prev-trit})))

(defn start-listener!
  "Start a TCP listener for WLC reservoir ticks.
   Calls tick-fn with each trit-tick as it arrives.
   Returns a map with :server and :future for cleanup."
  ([tick-fn] (start-listener! tick-fn 9998))
  ([tick-fn port]
   (let [server (ServerSocket. port)
         fut (future
               (try
                 (while (not (.isClosed server))
                   (let [sock (.accept server)
                         dis (DataInputStream. (.getInputStream sock))]
                     (future
                       (try
                         (loop [prev-trit 0]
                           (when-let [msg (read-frame dis)]
                             (if-let [tick (handle-notification msg prev-trit)]
                               (do (tick-fn tick)
                                   (recur (long (:tick/s-new tick))))
                               (recur prev-trit))))
                         (catch Exception e
                           (when-not (.isClosed server)
                             (println "WLC client error:" (.getMessage e))))
                         (finally (.close sock))))))
                 (catch Exception e
                   (when-not (.isClosed server)
                     (println "WLC listener error:" (.getMessage e))))))]
     {:server server :future fut :port port})))

(defn stop-listener!
  "Stop the WLC TCP listener."
  [{:keys [server future]}]
  (.close ^ServerSocket server)
  (future-cancel future))

(comment
  ;; Start listener that prints each tick
  (def listener (start-listener! prn))

  ;; Stop it
  (stop-listener! listener)

  ;; Start listener that transacts into gatomic
  ;; (def conn (gatomic.db/create-db))
  ;; (def listener (start-listener!
  ;;   (fn [tick]
  ;;     @(d/transact conn [(assoc tick
  ;;       :tick/uid (str (:tick/site tick) ":" (:tick/sweep tick)))]))))
  )
