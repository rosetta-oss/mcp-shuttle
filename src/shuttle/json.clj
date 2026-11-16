;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.json
  "The one place JSON is read and written. Keys are read as keywords."
  (:refer-clojure :exclude [read])
  (:require [clojure.data.json :as json]
            [clojure.walk :as walk]))

(defn read
  "Decodes a JSON string; object keys become keywords. Throws on invalid JSON."
  [s]
  (json/read-str s :key-fn keyword))

(defn- qualified [k] (if-let [ns (namespace k)] (str ns "/" (name k)) (name k)))

(defn write
  "Encodes a value as a JSON string, leaving `/` and non-ASCII characters as they are.
   Keywords, as keys and as values, keep their namespace (`:event/time` → \"event/time\"):
   data.json alone would drop it, and two keys differing only by namespace would collide."
  [x]
  (json/write-str (walk/postwalk #(if (keyword? %) (qualified %) %) x)
                  :escape-slash false :escape-unicode false))
