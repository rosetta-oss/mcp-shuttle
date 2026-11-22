;; Copyright (c) 2026 mcp-shuttle contributors
;; SPDX-License-Identifier: EPL-2.0

(ns shuttle.client
  "MCP client over Streamable HTTP, for a service calling another service's tools.

   Every failure throws an `ex-info`; nothing is printed and nothing returns nil:
   - HTTP status outside 2xx      → \"<label> <status> on <op>\", data `{:status …}`, and for a
                                     429 `:retry-after-secs` when the server says
   - JSON-RPC `error`              → \"<label> <op>: <message>\", data `{:code …}`
   - tool result with `isError`    → \"<label> <tool>: <text>\", data `{:result …}`; when the text
                                     is a JSON object, also `:error` (it, keys as keywords) and
                                     its `:code`, for a program to branch on
   - body that is not valid JSON   → \"<label> <op>: unreadable answer\"
   - no answer within `:timeout-ms` → \"<label> timed out on <op>\", data `{:timeout-ms …}`
   - no connection                 → \"<label> unreachable on <op>\", the IOException as cause
   `<label>` is `:label` (default \"MCP\"); `<op>` is the tool name for a call, else the method.
   No message or data ever carries the credential.

   A server answering a POST with `text/event-stream` is read until the response with the
   request's id; other events on that stream are skipped. A server that hands out a session
   (`Mcp-Session-Id`) gets it back on every request; a 404 on a request that carried one means
   the server forgot it, so the client initializes again once and repeats the request.

   ```clojure
   (call-tool {:url \"http://host:port/mcp\" :bearer key} \"templates\" {:limit 200})
   ```
   opens, initializes, calls and closes. For several calls, `connect` once, `call-tool` on the
   connection, then `close`: that saves the handshake each time. Either way the HttpClient and
   its TCP connections are shared and reused."
  (:require [clojure.string :as str]
            [shuttle.json :as json])
  (:import (java.io BufferedReader InputStream InputStreamReader)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers HttpTimeoutException)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent ExecutionException Future TimeUnit TimeoutException)))

(def protocol-version
  "The revision this client asks for."
  "2025-06-18")

(def supported-versions
  "Revisions whose tools/call this client understands; a server answering another one is refused."
  #{"2025-11-25" "2025-06-18" "2025-03-26"})

(def ^:private default-timeout-ms 60000)

(defonce ^:private http-clients (atom {}))

(defn- http-client
  "One HttpClient per connect timeout, shared by every connection: an
   HttpClient owns threads and a pool of TCP connections, so one per call
   would leave both behind until garbage collection and open a new socket
   each time. It holds no credential; those travel per request."
  ^HttpClient [{:keys [connect-timeout-ms] :or {connect-timeout-ms 20000}}]
  (let [made (delay (-> (HttpClient/newBuilder)
                        (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                        (.build)))]
    @(get (swap! http-clients #(if (contains? % connect-timeout-ms) % (assoc % connect-timeout-ms made)))
          connect-timeout-ms)))

;; ---------------------------------------------------------------------------
;; One exchange
;; ---------------------------------------------------------------------------

(defn- fail
  ([conn op what data] (throw (ex-info (str (:label conn) " " op ": " what) data)))
  ([msg data] (throw (ex-info msg data))))

(defn- request ^HttpRequest [conn method body]
  (let [{:keys [url bearer headers timeout-ms]} conn
        {:keys [session-id version]} @(:state conn)
        b (cond-> (HttpRequest/newBuilder (URI/create url))
            true (.header "Accept" "application/json, text/event-stream")
            bearer (.header "Authorization" (str "Bearer " bearer))
            session-id (.header "Mcp-Session-Id" session-id)
            version (.header "MCP-Protocol-Version" version)
            true (.timeout (Duration/ofMillis timeout-ms)))]
    (doseq [[k v] headers] (.header b (str k) (str v)))
    (case method
      :post (-> b (.header "Content-Type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString (json/write body))) (.build))
      :delete (-> b (.DELETE) (.build)))))

(defn- media-type [^HttpResponse resp]
  (some-> (.orElse (.firstValue (.headers resp) "content-type") nil)
          (str/split #";") first str/trim str/lower-case))

(defn- read-json [conn op ^String text]
  (try (json/read text)
       (catch Exception _ (fail conn op "unreadable answer" {}))))

(defn- sse-messages
  "The `data` of each event on an SSE body, lazily, as strings."
  [^InputStream in]
  (let [rd (BufferedReader. (InputStreamReader. in StandardCharsets/UTF_8))]
    (letfn [(step [buf]
              (lazy-seq
               (let [line (.readLine rd)]
                 (cond
                   (nil? line) (when (seq buf) [(str/join "\n" buf)])
                   (str/blank? line) (if (seq buf) (cons (str/join "\n" buf) (step [])) (step []))
                   (str/starts-with? line "data:") (step (conj buf (str/triml (subs line 5))))
                   :else (step buf)))))]
      (step []))))

(defn- answer
  "The JSON-RPC message answering `id` in a 2xx response, or nil for 202 / an empty body."
  [conn op id ^HttpResponse resp]
  (let [^InputStream in (.body resp)]
    (with-open [_ in]
      (case (media-type resp)
        "text/event-stream"
        (or (some (fn [data]
                    (let [m (read-json conn op data)]
                      (when (and (map? m) (= id (:id m)) (or (contains? m :result) (contains? m :error)))
                        m)))
                  (sse-messages in))
          (fail conn op "the event stream ended without an answer" {}))
        (let [text (String. (.readAllBytes in) StandardCharsets/UTF_8)]
          (when-not (str/blank? text)
            (let [m (read-json conn op text)]
              (if (sequential? m) (some #(when (= id (:id %)) %) m) m))))))))

(defn- exchange
  "Sends one message and returns the HTTP response; the body is still open."
  ^HttpResponse [conn op method body]
  (try
    (.send ^HttpClient (:http conn) (request conn method body) (HttpResponse$BodyHandlers/ofInputStream))
    (catch HttpTimeoutException _
      (throw (ex-info (str (:label conn) " timed out on " op) {:timeout-ms (:timeout-ms conn)})))
    (catch java.io.IOException e
      (throw (ex-info (str (:label conn) " unreachable on " op) {} e)))))

(defn- with-deadline
  "Runs f on another thread and gives up after the connection's timeout: an SSE body can stay
   open past the request timeout, which only covers the response head."
  [conn op f]
  (let [^Future fut (future (f))]
    (try (.get fut (:timeout-ms conn) TimeUnit/MILLISECONDS)
         (catch TimeoutException _
           (.cancel fut true)
           (fail (str (:label conn) " timed out on " op) {:timeout-ms (:timeout-ms conn)}))
         (catch ExecutionException e (throw (or (.getCause e) e))))))

(defn- drain [^HttpResponse resp]
  (with-open [^InputStream in (.body resp)] (.readAllBytes in)))

(defn- status-error [conn op ^HttpResponse resp]
  (let [text (try (String. ^bytes (drain resp) StandardCharsets/UTF_8) (catch Exception _ ""))
        m (try (json/read text) (catch Exception _ nil))
        code (when (map? m) (get-in m [:error :code]))
        retry (some-> (.orElse (.firstValue (.headers resp) "retry-after") nil) str/trim parse-long)]
    (fail (str (:label conn) " " (.statusCode resp) " on " op)
          (cond-> {:status (.statusCode resp)}
            code (assoc :code code)
            (and retry (= 429 (.statusCode resp))) (assoc :retry-after-secs retry)))))

(defn- remember-session! [conn ^HttpResponse resp]
  (when-let [sid (.orElse (.firstValue (.headers resp) "mcp-session-id") nil)]
    (swap! (:state conn) assoc :session-id sid)))

(declare initialize! close)

(defn- rpc!
  "Sends a request (with an id) or a notification (without) and returns the JSON-RPC result,
   or nil for a notification."
  [conn op method params & {:keys [retried?]}]
  (let [id (when-not (str/starts-with? method "notifications/") (swap! (:ids conn) inc))
        msg (cond-> {:jsonrpc "2.0" :method method} id (assoc :id id) params (assoc :params params))
        had-session (:session-id @(:state conn))
        out (with-deadline conn op
              (fn []
                (let [resp (exchange conn op :post msg)
                      status (.statusCode resp)]
                  (cond
                    (and (= 404 status) had-session (not retried?) (not= method "initialize"))
                    (do (drain resp) ::session-lost)

                    (not (<= 200 status 299)) (status-error conn op resp)

                    :else (do (remember-session! conn resp)
                              (if id
                                (or (answer conn op id resp) (fail conn op "no answer" {:status status}))
                                (do (drain resp) nil)))))))]
    (cond
      (= ::session-lost out)
      (do (swap! (:state conn) dissoc :session-id :version)
          (initialize! conn)
          (rpc! conn op method params :retried? true))

      (nil? out) nil

      (:error out)
      (fail conn op (or (get-in out [:error :message]) "error") {:code (get-in out [:error :code])})

      :else (:result out))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn initialize!
  "Handshake: `initialize`, then `notifications/initialized`. Returns the server's
   initialize result. `call-tool` on a fresh connection does this by itself."
  [conn]
  (let [res (rpc! conn "initialize" "initialize"
                  {:protocolVersion protocol-version
                   :capabilities {}
                   :clientInfo (:client-info conn)})
        v (:protocolVersion res)]
    (when-not (supported-versions v)
      (fail conn "initialize" (str "unsupported protocol version " (pr-str v)) {:version v}))
    (swap! (:state conn) assoc :version v :server res)
    (rpc! conn "initialize" "notifications/initialized" nil)
    res))

(defn connect
  "A connection: nothing is sent until the first call.
   Options:
   - `:url`         the MCP endpoint (required)
   - `:bearer`      sent as `Authorization: Bearer …`
   - `:headers`     more request headers
   - `:timeout-ms`  per request, including reading an event stream (default 60000)
   - `:connect-timeout-ms` for opening a TCP connection (default 20000)
   - `:http-client` a `java.net.http.HttpClient` of the host's own (proxy, TLS settings);
                    by default connections share one
   - `:label`       prefix of every error message (default \"MCP\")
   - `:client-info` `{:name … :version …}` sent on initialize"
  [{:keys [url timeout-ms label client-info] :as opts}]
  (when (str/blank? url) (throw (ex-info "shuttle.client/connect needs :url" {})))
  (merge (select-keys opts [:url :bearer :headers])
         {:timeout-ms (or timeout-ms default-timeout-ms)
          :label (or label "MCP")
          :client-info (or client-info {:name "mcp-shuttle" :version "0"})
          :http (or (:http-client opts) (http-client opts))
          :ids (atom 0)
          :state (atom {})}))

(defn- ready! [conn]
  (when-not (:version @(:state conn)) (initialize! conn))
  conn)

(declare listing)

(defn list-tools
  "The server's tools (follows `nextCursor`)."
  [conn]
  (listing conn "tools/list" :tools))

(defn call-tool
  "Calls a tool and returns its result (`{:content […] …}`, keys as keywords). A result with
   `isError` throws. Given options instead of a connection, opens one, calls, and closes it."
  [conn-or-opts tool args]
  (if (:http conn-or-opts)
    (let [res (rpc! (ready! conn-or-opts) tool "tools/call" {:name tool :arguments (or args {})})]
      (if (:isError res)
        (let [text (some :text (:content res))
              said (when (and text (str/starts-with? (str/triml text) "{"))
                     (try (json/read text) (catch Exception _ nil)))]
          (fail conn-or-opts tool (or text "tool error")
                (cond-> {:result res}
                  (map? said) (assoc :error said)
                  (and (map? said) (:code said)) (assoc :code (:code said)))))
        res))
    (let [conn (connect conn-or-opts)]
      (try (call-tool conn tool args)
           (finally (close conn))))))

(defn- listing [conn method k]
  (ready! conn)
  (loop [cursor nil acc []]
    (let [res (rpc! conn method method (when cursor {:cursor cursor}))
          acc (into acc (get res k))]
      (if-let [c (:nextCursor res)] (recur c acc) acc))))

(defn list-resources
  "The server's resources (follows `nextCursor`)."
  [conn]
  (listing conn "resources/list" :resources))

(defn read-resource
  "A resource's contents: `{:contents […]}`."
  [conn uri]
  (rpc! (ready! conn) uri "resources/read" {:uri uri}))

(defn list-prompts
  "The server's prompts (follows `nextCursor`)."
  [conn]
  (listing conn "prompts/list" :prompts))

(defn get-prompt
  "A prompt: `{:description … :messages […]}`."
  [conn prompt-name args]
  (rpc! (ready! conn) prompt-name "prompts/get" {:name prompt-name :arguments (or args {})}))

(defn structured
  "A tool result's `structuredContent`, or nil."
  [result]
  (:structuredContent result))

(defn text
  "The text of a tool result's first text block, or nil."
  [result]
  (some #(when (= "text" (:type %)) (:text %)) (:content result)))

(defn close
  "Ends the session if the server handed one out (`DELETE`, answer ignored)."
  [conn]
  (when (:session-id @(:state conn))
    (try (drain (exchange conn "close" :delete nil)) (catch Exception _ nil)))
  (reset! (:state conn) {})
  nil)
