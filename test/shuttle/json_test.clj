;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.json-test
  (:require [clojure.data.json :as dj]
            [clojure.test :refer [deftest is]]
            [shuttle.json :as json]))

(deftest keywords-keep-their-namespace
  (is (= "{\"event/time\":\"x\",\"plain\":\"a.b/c\",\"v\":[\"k\",\"n.s/k\"]}"
         (json/write (array-map :event/time "x" :plain :a.b/c :v [:k :n.s/k])))))

(deftest no-escaping-of-slash-or-unicode
  (is (= "\"a/b é\"" (json/write "a/b é"))))

(deftest the-same-as-data-json-where-no-keyword-has-a-namespace
  (let [dj-write #(dj/write-str % :escape-slash false :escape-unicode false)]
    (doseq [x [nil true false 0 -1 42 1.5 1.0E10 (float 2.5) 12345678901234567890N 1.50M (short 3) (byte 4)
               "" "plain" "quote \" backslash \\ slash / tab \t newline \n return \r" "control \u0001 \u001f"
               (str "separators " (char 0x2028) " " (char 0x2029)) "中文 é 😀" 'sym
               [] {} #{} [1 [2 [3]]] '(1 2) (map inc [1 2]) (sorted-map :a 1 :b {:c [nil true]})
               {"s" 1 :k 2 3 4} {:rows [{:id 1 :name "x" :tags ["a" "b"] :ok true :n nil}]}
               (java.util.ArrayList. [1 "a"]) (java.util.HashMap. {"a" 1})
               #uuid "00000000-0000-0000-0000-000000000001" (java.util.Date. 0) (java.time.Instant/ofEpochSecond 0) 1/3
               :kw {:status :open}]]
      (is (= (dj-write x) (json/write x)) (pr-str x))
      (is (= (dj/read-str (dj-write x)) (dj/read-str (json/write x))) (pr-str x)))))

(deftest what-json-cannot-carry-throws
  (doseq [x [Double/NaN Double/POSITIVE_INFINITY {:a [Float/NaN]} {nil 1} (java.time.LocalDate/of 2026 1 1) (Object.)]]
    (is (thrown? Exception (json/write x)) (pr-str x))))

(deftest raw-goes-in-as-it-is
  (is (= "{\"a\":{\"b\":[1,2]},\"c\":1}" (json/write (array-map :a (json/raw "{\"b\":[1,2]}") :c 1)))))

(deftest round-trip
  (let [x {:a [1 2.5 nil true "s"] :b {:c "é/\n\"" :d []}}]
    (is (= x (json/read (json/write x))))))
