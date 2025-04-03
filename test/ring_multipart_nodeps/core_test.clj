(ns ring-multipart-nodeps.core-test
  (:require [clojure.test :refer :all]
            [ring-multipart-nodeps.core :refer :all]
            [ring-multipart-nodeps.temp-file :as temp-file]
            [clojure.java.io :as io])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream File FileInputStream]
           [java.util Arrays]))

(defn make-test-stream [body-str]
  (ByteArrayInputStream. (.getBytes ^String body-str "UTF-8")))

(defn bytes->string [bytes]
  (String. ^bytes bytes "UTF-8"))

(def test-boundary "----WebKitFormBoundary7MA4YWxkTrZu0gW")

(def test-body-standard
  (str "--" test-boundary "\r\n"
       "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
       "value1\r\n"
       "--" test-boundary "\r\n"
       "Content-Disposition: form-data; name=\"field2\"; filename=\"example.txt\"\r\n"
       "Content-Type: text/plain\r\n\r\n"
       "Content of example.txt.\r\n"
       "--" test-boundary "\r\n"
       "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
       "value1-again\r\n"
       "--" test-boundary "--\r\n"))

(def test-body-no-final-crlf
  (str "--" test-boundary "\r\n"
       "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
       "value1\r\n"
       "--" test-boundary "\r\n"
       "Content-Disposition: form-data; name=\"field2\"; filename=\"example.txt\"\r\n"
       "Content-Type: text/plain\r\n\r\n"
       "Content of example.txt.\r\n"
       "--" test-boundary "\r\n"
       "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
       "value1-again\r\n"
       "--" test-boundary "--"))

(def test-body-charset
  (str "--" test-boundary "\r\n"
       "Content-Disposition: form-data; name=\"with-charset\"\r\n"
       "Content-Type: text/plain; charset=ISO-8859-1\r\n\r\n"
       "value-with-charset\r\n"
       "--" test-boundary "--\r\n"))

(defn make-request [body]
  {:headers {"content-type" (str "multipart/form-data; boundary=" test-boundary)}
   :content-type (str "multipart/form-data; boundary=" test-boundary)
   :character-encoding "UTF-8"
   :body (make-test-stream body)
   :params {"query-param" "qval"}})

(deftest test-multipart-form-detection
  (testing "Detects multipart form correctly"
    (is (multipart-form? {:content-type "multipart/form-data; boundary=abc"}))
    (is (not (multipart-form? {:content-type "application/json"})))
    (is (not (multipart-form? {})))))

(deftest test-content-type-parsing
  (testing "Parses content type correctly"
    (is (= {:content-type "text/plain" :charset "utf-8"}
           (#'ring-multipart-nodeps.core/parse-content-type "text/plain; charset=utf-8")))
    (is (= {:content-type "multipart/form-data"}
           (#'ring-multipart-nodeps.core/parse-content-type "multipart/form-data")))
    (is (nil? (#'ring-multipart-nodeps.core/parse-content-type nil)))))

(deftest test-content-disposition-parsing
  (testing "Parses content disposition correctly"
    (is (= {:name "field1"}
           (#'ring-multipart-nodeps.core/parse-content-disposition
             "form-data; name=\"field1\"")))
    (is (= {:name "field2" :filename "example.txt"}
           (#'ring-multipart-nodeps.core/parse-content-disposition
             "form-data; name=\"field2\"; filename=\"example.txt\"")))
    (is (empty? (#'ring-multipart-nodeps.core/parse-content-disposition
                  "attachment")))
    (is (nil? (#'ring-multipart-nodeps.core/parse-content-disposition nil)))))

(deftest test-boundary-extraction
  (testing "Extracts boundary correctly"
    (is (= "abc123"
           (#'ring-multipart-nodeps.core/extract-boundary
             {:content-type "multipart/form-data; boundary=abc123"})))
    (is (= "quoted-boundary"
           (#'ring-multipart-nodeps.core/extract-boundary
             {:content-type "multipart/form-data; boundary=\"quoted-boundary\""})))
    (is (nil? (#'ring-multipart-nodeps.core/extract-boundary
                {:content-type "application/json"})))))

(deftest test-assoc-conj
  (testing "assoc-conj handles single and multiple values correctly"
    (is (= {:a "val"}
           (#'ring-multipart-nodeps.core/assoc-conj {} :a "val")))
    (is (= {:a ["val1" "val2"]}
           (#'ring-multipart-nodeps.core/assoc-conj {:a "val1"} :a "val2")))
    (is (= {:a ["val1" "val2" "val3"]}
           (#'ring-multipart-nodeps.core/assoc-conj {:a ["val1" "val2"]} :a "val3")))))

(deftest test-ends-with
  (testing "ends-with? correctly identifies byte array suffix"
    (is (#'ring-multipart-nodeps.core/ends-with?
          (.getBytes "test\r\n" "US-ASCII")
          (.getBytes "\r\n" "US-ASCII")))
    (is (not (#'ring-multipart-nodeps.core/ends-with?
               (.getBytes "test" "US-ASCII")
               (.getBytes "\r\n" "US-ASCII"))))
    (is (not (#'ring-multipart-nodeps.core/ends-with?
               (.getBytes "\r" "US-ASCII")
               (.getBytes "\r\n" "US-ASCII"))))))

(deftest test-multipart-stream-parsing
  (testing "Parses standard multipart request correctly"
    (let [request (make-request test-body-standard)
          processed (multipart-params-request request {:store (byte-array-store)})
          parsed (:multipart-params processed)]
      (is (= ["value1" "value1-again"] (get parsed "field1")))
      (is (= "Content of example.txt."
             (bytes->string (:bytes (get parsed "field2")))))))

  (testing "Handles requests without final CRLF"
    (let [request (make-request test-body-no-final-crlf)
          processed (multipart-params-request request {:store (byte-array-store)})
          parsed (:multipart-params processed)]
      (is (= ["value1" "value1-again"] (get parsed "field1")))
      (is (= "Content of example.txt."
             (bytes->string (:bytes (get parsed "field2")))))))

  (testing "Respects part-specific charset"
    (let [request (make-request test-body-charset)
          processed (multipart-params-request request {:encoding "UTF-8"
                                                      :store (byte-array-store)})
          parsed (:multipart-params processed)]
      (is (= "value-with-charset" (get parsed "with-charset"))))))

(deftest test-custom-storage
  (testing "Custom file storage function works"
    (let [processed-files (atom [])
          custom-store (fn [{:keys [filename content-type stream] :as file-info}]
                         (let [result (assoc file-info
                                             :custom-processed true
                                             :orig-filename filename)]
                           (swap! processed-files conj filename)
                           result))
          request (make-request test-body-standard)
          processed (multipart-params-request request {:store custom-store})
          parsed (:multipart-params processed)]
      (is (= true (:custom-processed (get parsed "field2"))))
      (is (= "example.txt" (:orig-filename (get parsed "field2"))))
      (is (= ["example.txt"] @processed-files)))))

(deftest test-binary-data-handling
  (testing "Handles binary data like PNG images correctly"
    ;; PNG file header starts with byte values that may exceed signed byte range
    ;; PNG signature: 137 80 78 71 13 10 26 10
    (let [png-header (byte-array [(byte -119) (byte 80) (byte 78) (byte 71)
                                  (byte 13) (byte 10) (byte 26) (byte 10)])
          ;; Create a simple multipart body with binary content
          test-body (str "--" test-boundary "\r\n"
                        "Content-Disposition: form-data; name=\"image\"; filename=\"test.png\"\r\n"
                        "Content-Type: image/png\r\n\r\n")
          test-body-stream (ByteArrayOutputStream.)
          _ (do
              (.write test-body-stream (.getBytes test-body "UTF-8"))
              (.write test-body-stream png-header)
              (.write test-body-stream (.getBytes (str "\r\n--" test-boundary "--\r\n") "UTF-8")))
          test-request {:headers {"content-type" (str "multipart/form-data; boundary=" test-boundary)}
                        :content-type (str "multipart/form-data; boundary=" test-boundary)
                        :character-encoding "UTF-8"
                        :params {}
                        :body (ByteArrayInputStream. (.toByteArray test-body-stream))}
          ;; Use the byte-array-store explicitly since we want to test with bytes
          processed (multipart-params-request test-request {:store (byte-array-store)})
          parsed (:multipart-params processed)]

      (is (map? (get parsed "image")))
      (is (= 8 (count (:bytes (get parsed "image")))))
      (is (= -119 (aget ^bytes (:bytes (get parsed "image")) 0)))
      (is (= "test.png" (:filename (get parsed "image"))))

      ;; Ensure exact byte data preservation
      (let [parsed-bytes (:bytes (get parsed "image"))]
        (is (= (alength png-header) (alength ^bytes parsed-bytes)))
        (is (Arrays/equals ^bytes png-header ^bytes parsed-bytes))))))


(deftest test-progress-tracking
  (testing "Progress tracking for file uploads"
    (let [size-kb 20
          file-size (* size-kb 1024)
          file-data (byte-array file-size)

          ;; Fill with random data efficiently
          _ (let [random (java.util.Random.)]
              (.nextBytes random file-data))

          ;; Create multipart body
          multipart-start (str "--" test-boundary "\r\n"
                              "Content-Disposition: form-data; name=\"file\"; filename=\"test-" size-kb "kb.bin\"\r\n"
                              "Content-Type: application/octet-stream\r\n\r\n")
          multipart-end (str "\r\n--" test-boundary "--\r\n")
          out (ByteArrayOutputStream.)
          _ (.write out (.getBytes multipart-start "UTF-8"))
          _ (.write out file-data)
          _ (.write out (.getBytes multipart-end "UTF-8"))

          ;; Create input stream from output bytes
          body-stream (ByteArrayInputStream. (.toByteArray out))

          ;; Create a mock request with the multipart body
          request {:content-type (str "multipart/form-data; boundary=" test-boundary)
                  :character-encoding "UTF-8"
                  :body body-stream}

          ;; Progress tracking
          progress-updates (atom [])
          file-updates (atom [])

          ;; Process request with progress tracking
          result (multipart-params-request
                   request
                   {:progress-fn (fn [req bytes content-length item-count]
                                   (swap! progress-updates conj bytes)
                                   (when (pos? item-count)
                                     (swap! file-updates conj bytes)))})]

      ;; Check that progress was tracked
      (is (> (count @progress-updates) 0) "Should have progress updates")
      (is (> (count @file-updates) 0) "Should have file-specific updates")

      ;; Verify progression increases
      (is (apply <= @progress-updates) "Progress should increase monotonically")

      ;; The actual size will be slightly different due to multipart overhead
      ;; and potential CRLF handling, so we check it's close to expected
      (let [last-progress (last @progress-updates)]
        (is (> last-progress (* file-size 0.9)) "Final progress should be close to file size"))

      ;; Verify upload was successful
      (is (map? (get-in result [:params "file"])))
      ;; Allow small difference due to potential CRLF handling
      (is (> (:size (get-in result [:params "file"])) (* file-size 0.9))))))

(deftest test-middleware
  (testing "wrap-multipart-params adds params to request"
    (let [handler-called (atom false)
          test-handler (fn [req]
                         (reset! handler-called true)
                         {:status 200
                          :body {:params (:params req)
                                 :multipart (:multipart-params req)}})
          wrapped-handler (wrap-multipart-params test-handler)
          response (wrapped-handler (make-request test-body-standard))]
      (is @handler-called)
      (is (= "value1" (get-in response [:body :params "field1" 0])))
      (is (= "qval" (get-in response [:body :params "query-param"])))
      (is (map? (get-in response [:body :multipart "field2"])))))

  (testing "middleware passes non-multipart request unchanged"
    (let [req {:content-type "application/json"
               :params {"existing" "param"}}
          handler (fn [r] r)
          wrapped (wrap-multipart-params handler)]
      (is (= req (wrapped req)))))

  (testing "middleware handles missing boundary"
    (let [req {:content-type "multipart/form-data" ; no boundary
               :params {"existing" "param"}}
          handler (fn [r] r)
          wrapped (wrap-multipart-params handler)]
      (is (= req (wrapped req)))))

  (testing "middleware returns error response on parse exception"
    (let [bad-body (str "--" test-boundary "\r\nInvalid Headers")
          bad-req (make-request bad-body)
          handler (fn [_] (throw (Exception. "Should not be called")))
          wrapped (wrap-multipart-params handler {:silent true})
          response (wrapped bad-req)]
      (is (= 500 (:status response)))
      (is (= "text/plain" (get-in response [:headers "Content-Type"])))))

  (testing "async handler receives parsed request and can handle errors"
    (let [respond-called (atom false)
          raise-called (atom false)
          handler (fn [req respond raise] (respond (assoc req :handler-saw-it true)))
          error-handler (fn [req respond raise] (raise (ex-info "Test error" {})))
          good-wrapped (wrap-multipart-params handler)
          error-wrapped (wrap-multipart-params error-handler)]

      (good-wrapped
        (make-request test-body-standard)
        #(do (reset! respond-called true)
             (is (:handler-saw-it %))
             (is (map? (:multipart-params %))))
        #(do (reset! raise-called true)))

      (is @respond-called)
      (is (not @raise-called))

      (reset! respond-called false)
      (reset! raise-called false)

      (error-wrapped
        (make-request test-body-standard)
        #(do (reset! respond-called true))
        #(do (reset! raise-called true)
             (is (instance? clojure.lang.ExceptionInfo %))))

      (is (not @respond-called))
      (is @raise-called))))


(deftest test-temp-file-integration
  (testing "Integration with ring-multipart-nodeps.temp-file namespace"
    (let [file-content "test file content"
          file-bytes (.getBytes file-content "UTF-8")

          ;; Create test request
          test-body (str "--" test-boundary "\r\n"
                       "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n"
                       "Content-Type: text/plain\r\n\r\n"
                       file-content "\r\n"
                       "--" test-boundary "--\r\n")
          request (make-request test-body)

          ;; Use temp-file namespace's implementation with custom expiry
          temp-file-custom-store (temp-file/temp-file-store {:expires-in 60})
          result (multipart-params-request request {:store temp-file-custom-store})
          file-param (get-in result [:multipart-params "file"])]

      ;; Verify the file was properly stored and has expected properties
      (is (map? file-param))
      (is (= (:filename file-param) "test.txt"))
      (is (= (:content-type file-param) "text/plain"))
      (is (= (:size file-param) (alength file-bytes)))
      (is (instance? java.io.File (:tempfile file-param)))
      (is (.exists ^File (:tempfile file-param)))
      (is (= (slurp (:tempfile file-param)) file-content)))))


(deftest test-performance
  (testing "Performance with small file"
    (let [size-kb 200
          file-size (* size-kb 1024)
          file-data (byte-array file-size)

          ;; Fill with random data
          _ (let [random (java.util.Random.)]
              (.nextBytes random file-data))

          ;; Create multipart body
          multipart-start (str "--" test-boundary "\r\n"
                              "Content-Disposition: form-data; name=\"file\"; filename=\"test-" size-kb "kb.bin\"\r\n"
                              "Content-Type: application/octet-stream\r\n\r\n")
          multipart-end (str "\r\n--" test-boundary "--\r\n")
          out (ByteArrayOutputStream.)
          _ (.write out (.getBytes multipart-start "UTF-8"))
          _ (.write out file-data)
          _ (.write out (.getBytes multipart-end "UTF-8"))

          ;; Create input stream from output bytes
          body-stream (ByteArrayInputStream. (.toByteArray out))

          ;; Create a mock request with the multipart body
          request {:content-type (str "multipart/form-data; boundary=" test-boundary)
                  :character-encoding "UTF-8"
                  :body body-stream}

          ;; Measure parse time with temp-file-store (default)
          start-time (System/nanoTime)
          _ (multipart-params-request request)
          end-time (System/nanoTime)

          ;; Calculate metrics
          parse-time-ms (/ (- end-time start-time) 1000000.0)
          speed-kbps (/ (* size-kb 1000) parse-time-ms)]

      (println (format "Performance test results for %d KB file using temp-file-store:" size-kb))
      (println (format "  - Parse time: %.2f ms" parse-time-ms))
      (println (format "  - Processing speed: %.2f KB/s" speed-kbps))

      (is (> parse-time-ms 0))
      (is (> speed-kbps 0)))))

(deftest test-chunk-boundary-integrity
  (testing "Data integrity across chunk boundaries"
    (let [chunk-size 8192 ; Match the chunk size used in implementation
          total-size (* 3 chunk-size) ; Data spanning multiple chunks

          ;; Create test data with sequential bytes
          original-data (byte-array total-size)
          _ (doseq [i (range total-size)]
              (aset original-data i (unchecked-byte (mod i 256))))

          ;; Add special marker patterns at chunk boundaries for verification
          _ (doseq [pos (range chunk-size (- total-size 10) chunk-size)]
              (let [pattern (byte-array [(unchecked-byte 252) (unchecked-byte 253)
                                         (unchecked-byte 254) (unchecked-byte 255)
                                         (unchecked-byte 0) (unchecked-byte 1)])]
                (System/arraycopy pattern 0 original-data pos (alength pattern))))

          ;; Create multipart request
          multipart-start (str "--" test-boundary "\r\n"
                            "Content-Disposition: form-data; name=\"file\"; filename=\"test-data.bin\"\r\n"
                            "Content-Type: application/octet-stream\r\n\r\n")
          multipart-end (str "\r\n--" test-boundary "--\r\n")
          out (ByteArrayOutputStream.)
          _ (.write out (.getBytes multipart-start "UTF-8"))
          _ (.write out original-data)
          _ (.write out (.getBytes multipart-end "UTF-8"))

          ;; Create request and process it
          request {:content-type (str "multipart/form-data; boundary=" test-boundary)
                  :character-encoding "UTF-8"
                  :body (ByteArrayInputStream. (.toByteArray out))}
          result (multipart-params-request request {:store (byte-array-store)})
          result-data (get-in result [:multipart-params "file"])
          processed-data (:bytes result-data)]

      ;; Check size
      (is (= (alength original-data) (alength ^bytes processed-data))
          "Original and processed data sizes should match")

      ;; Check marker patterns at chunk boundaries
      (doseq [boundary-pos (range chunk-size (- total-size 10) chunk-size)]
        (let [expected (vec (take 6 (map #(bit-and % 0xFF)
                                        (for [i (range 6)] (aget ^bytes original-data (+ boundary-pos i))))))
              actual (vec (take 6 (map #(bit-and % 0xFF)
                                      (for [i (range 6)] (aget ^bytes processed-data (+ boundary-pos i))))))]

          (is (= expected actual)
              (format "Data at chunk boundary %d should be preserved" boundary-pos))))

      ;; Check full data integrity
      (is (Arrays/equals ^bytes original-data ^bytes processed-data)
          "Data should be preserved exactly across chunk boundaries"))))
