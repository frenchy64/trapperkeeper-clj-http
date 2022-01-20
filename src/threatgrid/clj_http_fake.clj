(ns threatgrid.clj-http-fake
  "Requires optional dependencies:
  - org.clojure/math.combinatorics
  - ring/ring-codec
  - clj-http/clj-http"
  (:require [clj-http.client :as http]
            clj-http.core
            [clojure.string :as str]
            [clojure.math.combinatorics :as comb]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [ring.util.codec :as ring-codec]
            [schema.core :as s])
  (:import [java.util.regex Pattern]
           [java.util Map]
           [java.net URLEncoder URLDecoder]
           [org.apache.http HttpEntity]))

(defprotocol RouteMatcher
  (matches [address method request]))

;; PRIVATE implementation details

(def ^:dynamic *local-config* nil)

(defn- check-set-fake-routes!-result! [res]
  (assert (and (vector? res)
               (= 2 (count res)))
          "set-fake-routes! must return the result of clojure.core/swap-vals!"))

(defn ^:no-doc -with-set-fake-routes! [set-fake-routes! local? config f]
  {:pre [(boolean? local?)
         (map? config)]}
  (if local?
    (binding [*local-config* config]
      (f))
    (let [[prev :as setter1] (set-fake-routes! config)
          _ (check-set-fake-routes!-result! setter1)]
      (try (binding [*local-config* nil]
             (f))
           (finally
             (let [[actual-prev :as setter2] (set-fake-routes! prev)
                   _ (check-set-fake-routes!-result! setter2)]
               (assert (= actual-prev config) (str "Interleaved fake routes!!"
                                                 (pr-str {:actual-prev actual-prev
                                                          :config config})))))))))

(defmacro ^:no-doc -thk
  "Internal.

  Wrap a ~@body cleanly in a thunk.
 
  Body is not in tail position, so recur is not captured.
  eg, if body = ((recur))
 
  Body is not in pre/post position, so map syntax cannot be repurposed.
  eg, if body = ({:pre [foo]} bar)"
  [& body]
  `(fn [] (let [res# (do ~@body)] res#)))

(defn- defaults-or-value [defaults value]
  (if (contains? defaults value) (reverse (vec defaults)) (vector value)))

(defn- potential-server-ports-for [request-map]
  (defaults-or-value #{80 nil} (:server-port request-map)))

(defn- potential-uris-for [request-map]
  (defaults-or-value #{"/" "" nil} (:uri request-map)))

(defn- potential-schemes-for [request-map]
  (defaults-or-value #{:http nil} (keyword (:scheme request-map))))


(defn- potential-query-strings-for [request-map]
  (let [queries (defaults-or-value #{"" nil} (:query-string request-map))
        query-supplied (= (count queries) 1)]
    (if query-supplied
      (map (partial str/join "&") (comb/permutations (str/split (first queries) #"&|;")))
      queries)))

(defn- potential-alternatives-to [request]
  (let [schemes       (potential-schemes-for       request)
        server-ports  (potential-server-ports-for  request)
        uris          (potential-uris-for          request)
        query-strings (potential-query-strings-for request)
        ;;  cartesian-product will modulate right-most params before left-most params.
        ;;  By putting larger collections near the left, we have a higher likelihood
        ;;  of taking advantage of its laziness, and halting early
        combinations  (comb/cartesian-product query-strings schemes server-ports uris)]
    (map #(merge request (zipmap [:query-string :scheme :server-port :uri] %)) combinations)))

(defn- address-string-for [request-map]
  (let [{:keys [scheme server-name server-port uri query-string]} request-map]
    (str/join [(if (nil? scheme)       "" (format "%s://" (name scheme)))
               server-name
               (if (nil? server-port)  "" (format ":%s"   server-port))
               (if (nil? uri)          "" uri)
               (if (nil? query-string) "" (format "?%s"   query-string))])))

(defn- url-encode
  "encodes string into valid URL string"
  [string]
  (some-> string str (URLEncoder/encode "UTF-8") (.replace "+" "%20")))

(defn- query-params-match?
  [expected-query-params request]
  (let [actual-query-params (or (some-> request :query-string ring-codec/form-decode) {})]
    (and (= (count expected-query-params) (count actual-query-params))
         (every? (fn [[k v]]
                   (= (str v) (get actual-query-params (name k))))
                 expected-query-params))))

(extend-protocol RouteMatcher
  String
  (matches [address method request]
    (matches (re-pattern (Pattern/quote address)) method request))

  Pattern
  (matches [address method request]
    (let [request-method (:request-method request)
          address-strings (map address-string-for (potential-alternatives-to request))]
      (and (contains? (set (distinct [:any request-method])) method)
           (some #(re-matches address %) address-strings))))
  Map
  (matches [address method request]
    (let [{expected-query-params :query-params} address]
      (and (or (nil? expected-query-params)
               (query-params-match? expected-query-params request))
           (let [request (cond-> request expected-query-params (dissoc :query-string))]
             (matches (:address address) method request))))))


(defn- flatten-routes [routes]
  (let [normalised-routes
        (reduce
         (fn [accumulator [address handlers]]
           (if (map? handlers)
             (into accumulator (map (fn [[method handler]] [method address handler]) handlers))
             (into accumulator [[:any address handlers]])))
         []
         routes)]
    (map #(zipmap [:method :address :handler] %) normalised-routes)))

(defn- utf8-bytes
    "Returns the UTF-8 bytes corresponding to the given string."
    [^String s]
    (.getBytes s "UTF-8"))


(let [byte-array-type (Class/forName "[B")]
  (defn- byte-array?
    "Is `obj` a java byte array?"
    [obj]
    (instance? byte-array-type obj)))

(defn- body-bytes
  "If `obj` is a byte-array, return it, otherwise use `utf8-bytes`."
  [obj]
  (if (byte-array? obj)
    obj
    (utf8-bytes obj)))

(defn- unwrap-body [request]
  (if (instance? HttpEntity (:body request))
    (assoc request :body (.getContent ^HttpEntity (:body request)))
    request))

(defn- get-matching-route
  [fake-routes request]
  (->> fake-routes
       flatten-routes
       (filter #(matches (:address %) (:method %) request))
       first))

(defn- handle-request-for-route
  [request route]
  (let [route-handler (:handler route)
        response (merge {:status 200 :body ""}
                        (route-handler (unwrap-body request)))]
    (assoc response :body (body-bytes (:body response)))))

(defn- throw-no-fake-route-exception
  [request]
  (throw (Exception.
           ^String
           (apply format
                  "No matching fake route found to handle request. Request details: \n\t%s \n\t%s \n\t%s \n\t%s \n\t%s "
                  (select-keys request [:scheme :request-method :server-name :uri :query-string])))))


(defn- get-current-fake-routes-config
  [deref-fake-routes]
  (or *local-config* (deref-fake-routes)))

;; PUBLIC API

(defn try-intercept
  ([origfn deref-fake-routes request respond raise]
   (let [{:keys [fake-routes in-isolation?]} (get-current-fake-routes-config deref-fake-routes)]
     (if-let [matching-route (get-matching-route fake-routes request)]
       (try (respond (handle-request-for-route request matching-route))
            (catch Exception e (raise e)))
       (if in-isolation?
         (throw-no-fake-route-exception request)
         (origfn request respond raise)))))
  ([origfn deref-fake-routes request]
   (let [{:keys [fake-routes in-isolation?]} (get-current-fake-routes-config deref-fake-routes)]
     (if-let [matching-route (get-matching-route fake-routes request)]
       (handle-request-for-route request matching-route)
       (if in-isolation?
         (throw-no-fake-route-exception request)
         (origfn request))))))

(defmacro with-fake-routes
  "Makes all wrapped clj-http requests first match against given routes.
  The actual HTTP request will be sent only if no matches are found.
  
  Like clj-http.fake/with-fake-routes, except takes a setter function.

  Local to the current thread."
  [set-fake-routes! routes & body]
  `(-with-set-fake-routes!
     ~set-fake-routes!
     true
     {:fake-routes ~routes}
     (-thk ~@body)))

(defmacro with-fake-routes-in-isolation
  "Makes all wrapped clj-http requests first match against given routes.
  If no route matches, an exception is thrown.

  Like clj-http.fake/with-fake-routes-in-isolation, except, except takes a setter function.

  Local to the current thread."
  [set-fake-routes! routes & body]
  `(-with-set-fake-routes!
     ~set-fake-routes!
     true
     {:fake-routes ~routes
      :in-isolation? true}
     (-thk ~@body)))

(defmacro with-global-fake-routes-in-isolation
  "Like clj-http.fake/with-global-fake-routes-in-isolation, except takes a setter function."
  [set-fake-routes! routes & body]
  `(-with-set-fake-routes!
     ~set-fake-routes!
     false
     {:fake-routes ~routes
      :in-isolation? true}
     (-thk ~@body)))

(defmacro with-global-fake-routes
  "Like clj-http.fake/with-global-fake-routes, except takes a setter function."
  [set-fake-routes! routes & body]
  `(-with-set-fake-routes!
     ~set-fake-routes!
     false
     {:fake-routes ~routes}
     (-thk ~@body)))
