(ns gatomic.vcvrack
  "Time-travel sonification for VCV Rack via OSC.

   Architecture:
     gatomic d/as-of → trit-tick history → OSC bundles → VCV Rack modules
     nanoclj-zig vcv_bridge.zig → direct CV/gate buffer (zero-copy path)

   VCV Rack expects CV signals in the -10V to +10V range.
   We map trit-tick parameters to CV:
     V/Oct pitch (trit)  → -1V / 0V / +1V  (C3 / C4 / C5)
     Gate (flicker)      → 0V or 10V
     Filter CV (mu)      → 0-5V
     Pan CV (color hue)  → -5V to +5V
     Tau CV (duration)   → 0-10V (WLC residence time, log scaled)

   Two output modes:
     1. OSC → VCV Rack's OSC module (network, any platform)
     2. Raw f32 frames → nanoclj-zig vcv_bridge.zig (shared memory, zero-copy)

   Time travel: scrub through Datomic history, replay trit-ticks at any
   transaction instant. The 'd/as-of' snapshots become 'scenes' you can
   sequence, crossfade, or A/B compare spectrally."
  (:require [gatomic.gorj :as gorj]
            [gatomic.color :as color]
            [gatomic.sonify :as sonify]
            [clojure.data.json :as json])
  (:import [java.net DatagramSocket DatagramPacket InetAddress]
           [java.nio ByteBuffer ByteOrder]
           [java.io RandomAccessFile]
           [java.nio.channels FileChannel FileChannel$MapMode]))

;; ---------------------------------------------------------------------------
;; CV mapping: trit-tick → VCV Rack voltage range
;; ---------------------------------------------------------------------------

(def ^:const VOCT-MAP
  "Trit → V/Oct. One semitone = 1/12V, one octave = 1V.
   -1 = C3 (0V), 0 = C4 (+1V), +1 = C5 (+2V)."
  {-1 0.0
    0 1.0
    1 2.0})

(defn tick->cv-frame
  "Convert a single trit-tick to a CV frame (vector of voltages).
   Frame layout: [voct gate filter pan tau sweep]
   6 channels, each -10V to +10V."
  [tick]
  (let [s-new  (long (:tick/s-new tick 0))
        mu     (long (:tick/mu tick 0))
        tau    (double (:tick/tau tick 0))
        sweep  (long (:tick/sweep tick 0))
        flicker (:tick/flicker tick :steady)
        hex    (:tick/color tick)]
    [(get VOCT-MAP s-new 1.0)                          ; ch0: V/Oct
     (if (= flicker :persistent-shadow) 0.0 10.0)     ; ch1: gate
     (case mu  0 5.0  1 1.0  -1 3.0  2.5)             ; ch2: filter CV
     (* 5.0 (sonify/color->pan hex))                   ; ch3: pan CV (-5..+5)
     (min 10.0 (* 2.0 (Math/log1p (Math/abs tau))))   ; ch4: tau CV (0..10)
     (min 10.0 (* (/ sweep 100.0) 10.0))]))            ; ch5: sweep position

;; ---------------------------------------------------------------------------
;; OSC output (UDP to VCV Rack OSC module)
;; ---------------------------------------------------------------------------

(def ^:const OSC-ADDR "/gatomic/cv")

(defn encode-osc-float [^float f]
  (let [bb (ByteBuffer/allocate 4)]
    (.order bb ByteOrder/BIG_ENDIAN)
    (.putFloat bb f)
    (.array bb)))

(defn pad4 [^bytes bs]
  (let [n (count bs)
        padded (+ n (mod (- 4 (mod n 4)) 4))]
    (byte-array padded (concat bs (repeat (- padded n) (byte 0))))))

(defn encode-osc-message
  "Encode an OSC message: address string + float args."
  [address floats]
  (let [addr-bytes (pad4 (.getBytes (str address "\0") "UTF-8"))
        type-tag (pad4 (.getBytes (str "," (apply str (repeat (count floats) "f")) "\0") "UTF-8"))
        float-bytes (byte-array (mapcat encode-osc-float floats))]
    (byte-array (concat (seq addr-bytes) (seq type-tag) (seq float-bytes)))))

(defn send-osc!
  "Send an OSC message via UDP."
  [^DatagramSocket sock ^String host ^long port address floats]
  (let [data (encode-osc-message address floats)
        addr (InetAddress/getByName host)
        packet (DatagramPacket. data (count data) addr (int port))]
    (.send sock packet)))

(defn osc-sender
  "Create an OSC sender. Returns {:socket sock :send! fn :close! fn}."
  ([] (osc-sender "127.0.0.1" 9000))
  ([host port]
   (let [sock (DatagramSocket.)]
     {:socket sock
      :send! (fn [floats] (send-osc! sock host port OSC-ADDR (map float floats)))
      :close! (fn [] (.close sock))})))

;; ---------------------------------------------------------------------------
;; Shared memory output (mmap for nanoclj-zig vcv_bridge.zig)
;; ---------------------------------------------------------------------------

(def ^:const FRAME-CHANNELS 6)
(def ^:const RING-FRAMES 4096)
(def ^:const HEADER-BYTES 16)  ; write-pos(u32) + read-pos(u32) + sample-rate(f32) + channels(u32)
(def ^:const RING-BYTES (+ HEADER-BYTES (* RING-FRAMES FRAME-CHANNELS 4)))
(def ^:const MMAP-PATH "/tmp/gatomic-vcv.shm")

(defn create-shm!
  "Create the shared memory ring buffer file. Returns a ByteBuffer."
  []
  (let [raf (RandomAccessFile. MMAP-PATH "rw")]
    (.setLength raf RING-BYTES)
    (let [ch (.getChannel raf)
          bb (.map ch FileChannel$MapMode/READ_WRITE 0 RING-BYTES)]
      (.order bb ByteOrder/LITTLE_ENDIAN)
      ;; header: write-pos=0, read-pos=0, sample-rate=48000, channels=6
      (.putInt bb 0 0)
      (.putInt bb 4 0)
      (.putFloat bb 8 48000.0)
      (.putInt bb 12 FRAME-CHANNELS)
      (.force bb)
      {:buffer bb :raf raf :channel ch})))

(defn write-frame!
  "Write a CV frame to the shared memory ring buffer."
  [^ByteBuffer bb frame]
  (let [write-pos (.getInt bb 0)
        offset (+ HEADER-BYTES (* write-pos FRAME-CHANNELS 4))]
    (doseq [i (range FRAME-CHANNELS)]
      (.putFloat bb (+ offset (* i 4)) (float (nth frame i 0.0))))
    (.putInt bb 0 (int (mod (inc write-pos) RING-FRAMES)))))

;; ---------------------------------------------------------------------------
;; Time-travel playback: scrub Datomic history as VCV scenes
;; ---------------------------------------------------------------------------

(defn history-ticks
  "Query trit-ticks at a specific Datomic transaction time.
   Returns ticks as they existed at that instant."
  [db-at]
  ;; db-at is already a (d/as-of db t) snapshot
  ;; Pull all trit-ticks from that snapshot
  (let [tick-eids (map first
                       (#'datomic.api/q '[:find ?e
                                          :where [?e :tick/sweep _]]
                                        db-at))]
    (mapv #(into {} (#'datomic.api/pull db-at '[*] %)) tick-eids)))

(defn time-travel-play!
  "Scrub through Datomic history, sending trit-ticks as CV to VCV Rack.

   tx-instants: seq of transaction instants (from d/tx-range or d/log)
   output:      :osc or :shm
   tempo-ms:    ms between frames (default 500ms = 120 BPM)"
  ([conn tx-instants] (time-travel-play! conn tx-instants {}))
  ([conn tx-instants {:keys [output tempo-ms]
                      :or {output :osc tempo-ms 500}}]
   (let [sender (case output
                  :osc (osc-sender)
                  :shm (create-shm!))
         db-fn (fn [t] (#'datomic.api/as-of (#'datomic.api/db conn) t))]
     (try
       (doseq [t tx-instants]
         (let [db-at (db-fn t)
               ticks (history-ticks db-at)
               classified (gorj/flicker-classify ticks)]
           (doseq [tick classified]
             (let [frame (tick->cv-frame tick)]
               (case output
                 :osc ((:send! sender) frame)
                 :shm (write-frame! (:buffer sender) frame)))
             (Thread/sleep (long tempo-ms)))))
       (finally
         (case output
           :osc ((:close! sender))
           :shm (.close ^java.io.RandomAccessFile (:raf sender))))))))

;; ---------------------------------------------------------------------------
;; Speculative A/B: compare two d/with branches sonically
;; ---------------------------------------------------------------------------

(defn ab-compare!
  "Play two speculative branches side by side.
   Branch A on OSC port 9000, Branch B on OSC port 9001.
   Use VCV Rack's mixer to crossfade between realities."
  [db tx-a tx-b {:keys [tempo-ms] :or {tempo-ms 200}}]
  (let [db-a (#'datomic.api/with db tx-a)
        db-b (#'datomic.api/with db tx-b)
        ticks-a (gorj/flicker-classify (history-ticks (:db-after db-a)))
        ticks-b (gorj/flicker-classify (history-ticks (:db-after db-b)))
        sender-a (osc-sender "127.0.0.1" 9000)
        sender-b (osc-sender "127.0.0.1" 9001)
        n (max (count ticks-a) (count ticks-b))]
    (try
      (dotimes [i n]
        (when-let [ta (get ticks-a i)]
          ((:send! sender-a) (tick->cv-frame ta)))
        (when-let [tb (get ticks-b i)]
          ((:send! sender-b) (tick->cv-frame tb)))
        (Thread/sleep (long tempo-ms)))
      (finally
        ((:close! sender-a))
        ((:close! sender-b))))))

;; ---------------------------------------------------------------------------
;; nanoclj-zig frame format (for vcv_bridge.zig consumption)
;; ---------------------------------------------------------------------------

(defn ticks->raw-f32
  "Serialize tick sequence to raw f32 binary for nanoclj-zig.
   Returns byte array: [n-frames(u32) n-channels(u32) frame0-ch0(f32) ...]"
  [ticks]
  (let [frames (mapv tick->cv-frame (gorj/flicker-classify ticks))
        n (count frames)
        bb (ByteBuffer/allocate (+ 8 (* n FRAME-CHANNELS 4)))]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (.putInt bb (int n))
    (.putInt bb (int FRAME-CHANNELS))
    (doseq [frame frames
            v frame]
      (.putFloat bb (float v)))
    (.array bb)))

(defn write-f32-file!
  "Write trit-tick CV frames to a binary file for nanoclj-zig."
  [ticks path]
  (let [bs (ticks->raw-f32 ticks)]
    (with-open [out (java.io.FileOutputStream. path)]
      (.write out bs))))

(comment
  ;; --- OSC to VCV Rack ---
  (let [sender (osc-sender)]
    (doseq [tick (gorj/flicker-classify (gorj/generate-ticks 24))]
      ((:send! sender) (tick->cv-frame tick))
      (Thread/sleep 250))
    ((:close! sender)))

  ;; --- Shared memory path ---
  (let [shm (create-shm!)]
    (doseq [tick (gorj/flicker-classify (gorj/generate-ticks 64))]
      (write-frame! (:buffer shm) (tick->cv-frame tick))
      (Thread/sleep 100))
    (.close (:raf shm)))

  ;; --- Write binary for nanoclj-zig ---
  (write-f32-file! (gorj/generate-ticks 69) "/tmp/gatomic-69.f32")

  ;; --- Time travel through DB history ---
  ;; (def conn (gatomic.db/create-db))
  ;; ... transact some ticks ...
  ;; (def log (d/log conn))
  ;; (def txs (map :t (d/tx-range log nil nil)))
  ;; (time-travel-play! conn txs {:output :osc :tempo-ms 300})

  ;; --- A/B speculative branches ---
  ;; (ab-compare! (d/db conn)
  ;;   [{:tick/uid "1069:0" :tick/s-new 1}]   ; branch A: force trit +1
  ;;   [{:tick/uid "1069:0" :tick/s-new -1}]   ; branch B: force trit -1
  ;;   {:tempo-ms 200})
  )
