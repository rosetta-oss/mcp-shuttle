;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.json
  "The one place JSON is read and written. Keys are read as keywords.

   Writing is this namespace's own single pass over the value: keywords, as
   keys and as values, keep their namespace (`:event/time` → \"event/time\";
   data.json alone would drop it, and two keys differing only by namespace
   would collide), and `/` and non-ASCII characters are left as they are.
   Values it does not write itself (dates, UUIDs, ratios…) go to data.json,
   so what cannot be written fails as it does there."
  (:refer-clojure :exclude [read])
  (:require [clojure.data.json :as json])
  (:import (java.util Collection Map Map$Entry)))

(defn read
  "Decodes a JSON string; object keys become keywords. Throws on invalid JSON."
  [s]
  (json/read-str s :key-fn keyword))

(deftype Raw [^String json])

(defn raw
  "Text that is already JSON: `write` puts it in as it is. For a value that has
   been written once and appears again."
  [^String json]
  (Raw. json))

(defn- write-string [^StringBuilder sb ^String s]
  (.append sb \")
  (let [n (.length s)]
    (loop [i 0 start 0]
      (if (< i n)
        (let [c (.charAt s i)
              esc (case c
                    \" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \tab "\\t"
                    \backspace "\\b" \formfeed "\\f"
                    ;; other control characters, and the two line separators JavaScript reads as line ends
                    (when (or (< (int c) 0x20) (= (int c) 0x2028) (= (int c) 0x2029))
                      (format "\\u%04x" (int c))))]
          (if esc
            (do (.append sb ^CharSequence s (int start) (int i))
                (.append sb ^String esc)
                (recur (inc i) (inc i)))
            (recur (inc i) start)))
        (.append sb ^CharSequence s (int start) (int n)))))
  (.append sb \"))

(defn- key-string ^String [k]
  (cond (string? k) k
        (keyword? k) (subs (str k) 1)
        (nil? k) (throw (Exception. "JSON object keys cannot be nil/null"))
        :else (str k)))

(defn- write* [^StringBuilder sb x]
  (cond
    (nil? x) (.append sb "null")
    (string? x) (write-string sb x)
    (keyword? x) (write-string sb (subs (str x) 1))
    (instance? Boolean x) (.append sb (if (.booleanValue ^Boolean x) "true" "false"))
    (or (instance? Long x) (instance? Integer x) (instance? BigDecimal x) (instance? clojure.lang.BigInt x)
        (instance? BigInteger x) (instance? Short x) (instance? Byte x))
    (.append sb (str x))
    (or (instance? Double x) (instance? Float x))
    (let [d (double x)]
      (when (or (Double/isNaN d) (Double/isInfinite d))
        (throw (Exception. (str "JSON error: cannot write " (if (Double/isNaN d) "NaN" "an infinite number")))))
      (.append sb (str x)))
    (instance? Raw x) (.append sb (.-json ^Raw x))
    (instance? Map x)
    (do (.append sb \{)
        (reduce (fn [first? ^Map$Entry e]
                  (when-not first? (.append sb \,))
                  (write-string sb (key-string (.getKey e)))
                  (.append sb \:)
                  (write* sb (.getValue e))
                  false)
                true x)
        (.append sb \}))
    (or (sequential? x) (instance? Collection x))
    (do (.append sb \[)
        (reduce (fn [first? v]
                  (when-not first? (.append sb \,))
                  (write* sb v)
                  false)
                true x)
        (.append sb \]))
    (symbol? x) (write-string sb (str x))
    :else (.append sb ^String (json/write-str x :escape-slash false :escape-unicode false)))
  sb)

(defn write
  "Encodes a value as a JSON string. Throws on a value JSON cannot carry."
  [x]
  (str (write* (StringBuilder.) x)))
