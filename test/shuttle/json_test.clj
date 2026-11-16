;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.json-test
  (:require [clojure.test :refer [deftest is]]
            [shuttle.json :as json]))

(deftest keywords-keep-their-namespace
  (is (= "{\"event/time\":\"x\",\"plain\":\"a.b/c\",\"v\":[\"k\",\"n.s/k\"]}"
         (json/write (array-map :event/time "x" :plain :a.b/c :v [:k :n.s/k])))))

(deftest no-escaping-of-slash-or-unicode
  (is (= "\"a/b é\"" (json/write "a/b é"))))
