(ns ring-multipart-nodeps.core
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [ring-multipart-nodeps.temp-file :as temp-file])
  (:import [java.io ByteArrayOutputStream InputStream PushbackInputStream ByteArrayInputStream]))

;; --- Configuration ---
(def ^:private default-buffer-size 8192) ; Read buffer size
(def ^:private max-header-size 8192)     ; Max bytes to read for headers per part
(def ^:private chunk-size 8192)          ; Chunk size for buffered reads
(def ^:private crlf-bytes (.getBytes "\r\n" "US-ASCII"))
(def ^:private crlf-len (alength crlf-bytes))

;; --- Utilities ---

(defn- assoc-conj
  "Associates a value with a key in a map. If the key already exists,
   the value is appended to a vector of values"
  [m k v]
  (assoc m k (if-let [cur (get m k)]
               (if (vector? cur) (conj cur v) [cur v])
               v)))

(defn multipart-form?
  "Returns true if the request is a multipart form request"
  [request]
  (when-let [content-type (:content-type request)]
    (str/starts-with? content-type "multipart/form-data")))

(defn- get-request-encoding [request]
  (or (:character-encoding request) "UTF-8"))

(defn- parse-content-type
  "Parses a Content-Type header into a map with :content-type and optional :charset keys"
  [^String ctype]
  (when ctype
    (let [parts (str/split ctype #";\s*")
          charset (when-let [charset-str (->> parts
                                           (rest)
                                           (map str/lower-case)
                                           (filter #(str/starts-with? % "charset="))
                                           (first))]
                    (second (str/split charset-str #"=")))]
      (if charset
        {:content-type (first parts)
         :charset charset}
        {:content-type (first parts)}))))

(defn- parse-content-disposition
  "Parses a Content-Disposition header into a map with :name, :filename, etc. keys"
  [^String disp]
  (when disp
    (let [parts (str/split disp #";\s*")
          disposition (first parts)]
      (if (= disposition "form-data")
        (->> (rest parts)
             (map #(str/split % #"=" 2))
             (keep (fn [[k v]] (when v [(keyword k) (str/replace v #"^\"|\"$|\\" "")]))) ; Remove quotes
             (into {}))
        {}))))

(defn- ends-with?
  "Returns true if the byte array ends with the specified suffix"
  [^bytes data ^bytes suffix]
  (let [data-len (alength data)
        suffix-len (alength suffix)]
    (and (>= data-len suffix-len)
         (java.util.Arrays/equals data (- data-len suffix-len) data-len suffix 0 suffix-len))))

;; --- Key optimized function ---

(defn- read-bytes-until
  "Optimized version that reads in chunks for better performance.
   Optional progress-fn will be called with total bytes read during processing."
  [^PushbackInputStream pbin ^bytes boundary & [progress-fn]]
  (let [boundary-len (alength boundary)
        buffer-size chunk-size
        buffer (byte-array buffer-size)
        out (ByteArrayOutputStream. default-buffer-size)
        first-boundary-byte (aget boundary 0)]  ; For quick first-byte check

    (loop [prev-buffer (byte-array 0)
           prev-buffer-valid-bytes 0]
      (let [read-size (.read pbin buffer 0 buffer-size)]
        (cond
          ;; EOF or no data
          (<= read-size 0)
          (do
            ;; Write any remaining bytes from previous chunk
            (when (pos? prev-buffer-valid-bytes)
              (.write out prev-buffer 0 prev-buffer-valid-bytes))
            ;; Return collected data or nil if nothing was read
            (if (> (.size out) 0)
              (.toByteArray out)
              nil))

          ;; Process chunk of data
          :else
          (let [found? (atom false)
                found-at (atom -1)
                found-in-prev? (atom false)]

            ;; Report progress if a progress function was provided
            (when progress-fn
              (progress-fn (+ (.size out) prev-buffer-valid-bytes read-size)))

            ;; First check for boundary that might span the previous and current buffer
            (when (and (pos? prev-buffer-valid-bytes)
                       (>= (+ prev-buffer-valid-bytes read-size) boundary-len))
              (loop [start-pos (max 0 (- prev-buffer-valid-bytes (dec boundary-len)))]
                (when (and (< start-pos prev-buffer-valid-bytes)
                           (not @found?))
                  (when (= (aget prev-buffer start-pos) first-boundary-byte)
                    ;; Potential match starting in prev-buffer
                    (let [fully-matches
                          (loop [j 0]
                            (cond
                              (>= j boundary-len)
                              true ; Full match

                              :else
                              (let [data-idx (+ start-pos j)]
                                (if (< data-idx prev-buffer-valid-bytes)
                                  ;; Data is in prev-buffer
                                  (if (= (aget prev-buffer data-idx) (aget boundary j))
                                    (recur (inc j))
                                    false) ; Mismatch

                                  ;; Data is in current buffer
                                  (let [current-buffer-idx (- data-idx prev-buffer-valid-bytes)]
                                    (if (< current-buffer-idx read-size)
                                      (if (= (aget buffer current-buffer-idx) (aget boundary j))
                                        (recur (inc j))
                                        false) ; Mismatch
                                      false))))))] ; Beyond available data

                      (when fully-matches
                        (reset! found? true)
                        (reset! found-in-prev? true)
                        (reset! found-at start-pos))))
                  (recur (inc start-pos)))))

            ;; If no boundary spans chunks, check current buffer
            (when (and (not @found?)
                       (>= read-size boundary-len))
              (loop [i 0]
                (when (and (< i (- read-size (dec boundary-len)))
                           (not @found?))
                  (when (= (aget buffer i) first-boundary-byte)
                    ;; Check for full boundary match starting at position i
                    (let [fully-matches
                          (loop [j 0]
                            (cond
                              (>= j boundary-len)
                              true ; Full match

                              :else
                              (let [data-idx (+ i j)]
                                (if (< data-idx read-size)
                                  ;; Check if the byte matches the boundary byte
                                  (if (= (aget buffer data-idx) (aget boundary j))
                                    (recur (inc j))
                                    false) ; Mismatch
                                  false))))] ; Beyond available data

                      (when fully-matches
                        (reset! found? true)
                        (reset! found-at i))))
                  (recur (inc i)))))

            ;; Handle found boundary or continue reading
            (if @found?
              ;; Found the boundary
              (do
                ;; Write everything up to the boundary
                (if @found-in-prev?
                  (do
                    ;; Write data from previous buffer up to found position
                    (.write out prev-buffer 0 @found-at)

                    ;; Calculate how many bytes of the boundary are in the current buffer
                    (let [boundary-bytes-in-prev (min boundary-len (- prev-buffer-valid-bytes @found-at))
                          boundary-bytes-in-current (- boundary-len boundary-bytes-in-prev)
                          remaining (- read-size boundary-bytes-in-current)]

                      ;; Push back remaining bytes after boundary
                      (when (pos? remaining)
                        (loop [i (dec read-size)]
                          (when (>= i (- read-size remaining))
                            (.unread pbin (aget buffer i))
                            (recur (dec i)))))))

                  (do
                    ;; Write all previous buffer data
                    (when (pos? prev-buffer-valid-bytes)
                      (.write out prev-buffer 0 prev-buffer-valid-bytes))

                    ;; Write current buffer data up to boundary
                    (.write out buffer 0 @found-at)

                    ;; Push back any remaining bytes after boundary
                    (let [remaining (- read-size (+ @found-at boundary-len))]
                      (when (pos? remaining)
                        (loop [i (dec read-size)]
                          (when (>= i (+ @found-at boundary-len))
                            (.unread pbin (aget buffer i))
                            (recur (dec i))))))))

                ;; Return collected data
                (.toByteArray out))

              ;; No boundary found
              (do
                ;; Write previous buffer data fully
                (when (pos? prev-buffer-valid-bytes)
                  (.write out prev-buffer 0 prev-buffer-valid-bytes))

                ;; Determine how much of the current buffer to write vs. save for next iteration
                (let [bytes-to-save (min (dec boundary-len) read-size)
                      bytes-to-write (- read-size bytes-to-save)]

                  ;; Write safe portion of current buffer
                  (when (pos? bytes-to-write)
                    (.write out buffer 0 bytes-to-write))

                  ;; Save last bytes of current buffer for next iteration
                  (let [new-prev-buffer (byte-array boundary-len)]
                    (when (pos? bytes-to-save)
                      (System/arraycopy buffer bytes-to-write new-prev-buffer 0 bytes-to-save))

                    ;; Continue reading
                    (recur new-prev-buffer bytes-to-save)))))))))))

(defn- read-crlf [^PushbackInputStream pbin]
  (let [cr (.read pbin)]
    (if (not= cr 13)
      (when (not= cr -1) (.unread pbin cr)) ; Push back if not CR (and not EOF)
      (let [lf (.read pbin)]                ; Only read LF if CR was found
        (if (not= lf 10)
          (when (not= lf -1) (.unread pbin lf)) ; Push back if not LF (and not EOF)
          true))))) ; Return true only if CRLF was fully consumed

;; --- Core Parsing Logic ---

(defn- parse-part-headers [^PushbackInputStream pbin encoding]
  (let [header-bytes (ByteArrayOutputStream.)
        boundary (.getBytes "\r\n\r\n" "US-ASCII")]
    (loop [b (.read pbin)
           match-idx 0
           read-count 0]
      (cond
        (= -1 b) (throw (ex-info "Unexpected EOF parsing part headers" {}))
        (> read-count max-header-size) (throw (ex-info "Max header size exceeded" {:max-size max-header-size}))

        (= (unchecked-byte b) (aget boundary match-idx))
        (if (= (inc match-idx) (alength boundary))
          (->> (String. (.toByteArray header-bytes) encoding)
               (str/split-lines)
               (map #(str/split % #":\s*" 2))
               (filter #(= 2 (count %)))
               (map (fn [[k v]] [(str/lower-case k) v]))
               (into {}))
          (recur (.read pbin) (inc match-idx) (inc read-count)))

        (> match-idx 0)
        (do
          (.write header-bytes boundary 0 match-idx)
          (.write header-bytes (unchecked-byte b))
          (recur (.read pbin) 0 (+ read-count match-idx 1)))

        :else
        (do
          (.write header-bytes (unchecked-byte b))
          (recur (.read pbin) 0 (inc read-count)))))))

(defn byte-array-store
  "Stores uploaded files in memory as byte arrays"
  [{:keys [filename content-type stream] :as file-info}]
  (with-open [out (ByteArrayOutputStream.)]
    (io/copy stream out)
    (assoc file-info :bytes (.toByteArray out) :size (.size out) :stream :consumed)))

(defn temp-file-store
  "Stores uploaded files to temporary files on disk.
   Never loads the entire file into memory."
  [{:keys [filename content-type stream] :as file-info}]
  (let [tempfile (java.io.File/createTempFile "ring-multipart-" (str "-" (or filename "upload")))
        _ (.deleteOnExit tempfile)
        written-bytes (atom 0)]
    (with-open [out (java.io.FileOutputStream. tempfile)]
      ;; Stream copy using a buffer, never loading entire file into memory
      (let [buffer (byte-array chunk-size)]
        (loop []
          (let [bytes-read (.read stream buffer 0 chunk-size)]
            (when (pos? bytes-read)
              (swap! written-bytes + bytes-read)
              (.write out buffer 0 bytes-read)
              (recur))))))
    (assoc file-info
           :tempfile tempfile
           :size @written-bytes
           :stream :consumed)))

;; Use the temp-file implementation from ring-multipart-nodeps.temp-file
(def ^:private default-store
  "Default store using ring-multipart-nodeps.temp-file/temp-file-store."
  (delay (temp-file/temp-file-store)))

(defn- parse-multipart-stream
  "Parse a multipart form data stream"
  [^InputStream body boundary-str opts]
  (let [{:keys [encoding fallback-encoding store progress-fn]} opts
        store            (or store @default-store)
        req-encoding     (get-request-encoding opts)
        fallback-encode  (or fallback-encoding req-encoding "UTF-8")
        boundary-bytes   (.getBytes (str "--" boundary-str) "US-ASCII")
        pbin             (PushbackInputStream. body default-buffer-size)
        bytes-read       (atom 0)]

    ;; Consume initial boundary line
    (let [first-boundary (read-bytes-until pbin boundary-bytes)]
      (when (or (nil? first-boundary) (not (zero? (alength first-boundary))))
        (throw (ex-info "Invalid multipart start" {:boundary boundary-str})))
      (read-crlf pbin))

    ;; Loop through parts
    (loop [params {}]
      (let [part-headers (parse-part-headers pbin fallback-encode)
            cd-header    (get part-headers "content-disposition")
            ct-header    (get part-headers "content-type")
            cd-info      (parse-content-disposition cd-header)
            ct-info      (parse-content-type ct-header)
            part-name    (:name cd-info)
            filename     (:filename cd-info)]

        (if-not part-name
          (throw (ex-info "Part missing name in Content-Disposition" {:headers part-headers})))

        (let [current-file? (boolean filename)
              ;; Pass the progress function if one was provided
              part-body-bytes (read-bytes-until pbin boundary-bytes
                                               (when progress-fn
                                                 (fn [bytes]
                                                   (progress-fn (:request opts) bytes (:content-length (:request opts)) (if current-file? 1 0)))))
              _               (when (nil? part-body-bytes) (throw (ex-info "Unexpected EOF reading part body" {:name part-name})))

              next-marker-type (let [char1 (.read pbin)
                                     char2 (.read pbin)]
                                 (cond
                                   (and (= char1 (int \-)) (= char2 (int \-)))
                                   (do (read-crlf pbin) :final) ; Consume optional trailing CRLF

                                   (and (= char1 13) (= char2 10)) ; Use direct int comparison
                                   :continue

                                   (= -1 char1)
                                   (throw (ex-info "Unexpected EOF immediately after boundary" {:name part-name}))

                                   :else
                                   (do (when (not= -1 char2) (.unread pbin char2))
                                       (when (not= -1 char1) (.unread pbin char1))
                                       (throw (ex-info "Invalid sequence after boundary" {:name part-name, :char1 char1, :char2 char2})))))

              ;; Trim trailing CRLF from all parts for consistency
              trimmed-body-bytes (let [body-len (alength part-body-bytes)]
                                  (if (ends-with? part-body-bytes crlf-bytes)
                                    (java.util.Arrays/copyOfRange part-body-bytes 0 (- body-len crlf-len))
                                    part-body-bytes))

              part-value (if filename
                           ;; File Upload
                           (store {:filename     filename
                                   :content-type (:content-type ct-info)
                                   :stream       (ByteArrayInputStream. trimmed-body-bytes)
                                   :part-headers part-headers})
                           ;; Regular Field
                           (let [field-charset (or encoding (:charset ct-info) fallback-encode)]
                             (String. ^bytes trimmed-body-bytes ^String field-charset)))

              new-params (assoc-conj params part-name part-value)]

          (if (= next-marker-type :final)
            new-params ; All done
            (recur new-params)))))))

(defn- extract-boundary [request]
  (when-let [content-type (:content-type request)]
    (second (re-find #"(?i)boundary=(?:\")?([^\";,]+)(?:\")?" content-type))))

;; --- Middleware ---

(defn multipart-params-request
 "Adds :multipart-params and :params keys to request by parsing
  multipart/form-data body. Based on ring's API but bb compatible.

  Options:
  :encoding          - Forced character encoding for fields. Overrides part Content-Type.
  :fallback-encoding - Encoding used if part has no Content-Type charset. Defaults
                       to request encoding or UTF-8.
  :store             - Function to handle file uploads. Takes map with
                       :filename, :content-type, :stream, :part-headers.
                       Default is the temp-file-store. For in-memory storage use
                       the byte-array-store function.
  :progress-fn       - a function that gets called during uploads. The
                       function should expect four parameters: request,
                       bytes-read, content-length, and item-count."
 ([request]
  (multipart-params-request request {}))
 ([request options]
  (if (and (:body request) (multipart-form? request))
    (if-let [boundary (extract-boundary request)]
      (let [encoding (or (:encoding options) ; Explicit override
                         (get-request-encoding request))
            merged-opts (merge {:fallback-encoding encoding} options {:request request})
            parsed-params (parse-multipart-stream (:body request) boundary merged-opts)]
        (merge request {:multipart-params parsed-params} {:params (merge (:params request) parsed-params)}))
      ;; Invalid boundary header - return request unmodified or with error marker?
      ;; Ring merges empty params, let's mimic that.
      (update request :params merge {}))
    ;; Not a multipart request or no body
    request)))

(defn wrap-multipart-params
 "Middleware to parse multipart parameters from a request. Adds the
  following keys to the request map:

  :multipart-params - a map of multipart parameters from the request body.
  :params           - merges multipart-params into existing :params.

  Accepts the following options:

  :encoding          - Forced character encoding for fields. Overrides part Content-Type.
  :fallback-encoding - Encoding used if part has no Content-Type charset. Defaults
                       to request encoding or UTF-8.
  :store             - Function to handle file uploads. Takes map with
                       :filename, :content-type, :stream, :part-headers.
                       Default is the temp-file-store. For in-memory storage use
                       the byte-array-store function.
  :progress-fn       - Function called during uploads with parameters: request,
                       bytes-read, content-length, item-count.

  Does simple error handling; throws exceptions on parsing errors."
 ([handler]
  (wrap-multipart-params handler {}))
 ([handler options]
  (fn handle-multipart-request
    ([request]
     (try
       (handler (multipart-params-request request options))
       (catch Exception e
         ;; Basic error response - A real application should use :error-handler
         (when-not (:silent options)
           (if (instance? clojure.lang.ExceptionInfo e)
             (println "Error parsing multipart request:" (:message (ex-data e)) e)
             (println "Error parsing multipart request:" e)))
         {:status 500 :headers {"Content-Type" "text/plain"} :body "Server error parsing multipart request"})))
    ([request respond raise]
     (try
       (handler (multipart-params-request request options) respond raise)
       (catch Exception e
         (raise e)))))))
