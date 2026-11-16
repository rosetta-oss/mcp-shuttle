;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.json
  "The one place JSON is read and written. Keys are read as keywords."
  (:refer-clojure :exclude [read])
  (:require [clojure.data.json :as json]))

(defn read
  "Decodes a JSON string; object keys become keywords. Throws on invalid JSON."
  [s]
  (json/read-str s :key-fn keyword))

(defn write
  "Encodes a value as a JSON string, leaving `/` and non-ASCII characters as they are."
  [x]
  (json/write-str x :escape-slash false :escape-unicode false))
