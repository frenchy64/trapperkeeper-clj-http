(ns threatgrid.clj-http-fake.global
  "The original clj-http.fake API.
  
  Requires optional dependencies:
  - robert/hooke
  - ring/ring-codec
  - org.clojure/math.combinatorics
  - clj-http/clj-http"
  (:require clj-http.core
            [robert.hooke :as hooke]
            [threatgrid.clj-http-fake :as impl]))

;; implementation

(defonce ^{:doc "Internal"}
  global-routes-atom
  (atom nil))

(defn set-fake-routes!
  "Internal"
  [routes]
  (swap-vals! global-routes-atom (constantly routes)))

(defn- try-intercept
  ([origfn request respond raise]
   (impl/try-intercept origfn #(deref global-routes-atom) request respond raise))
  ([origfn request]
   (impl/try-intercept origfn #(deref global-routes-atom) request)))

(defn- initialize-request-hook []
  (hooke/add-hook
    #'clj-http.core/request
    #'try-intercept))

(initialize-request-hook)

;; public API

(defmacro with-fake-routes
  "Makes all wrapped clj-http requests first match against given routes.
  The actual HTTP request will be sent only if no matches are found.

  Thread local."
  [routes & body]
  `(impl/with-fake-routes
     set-fake-routes!
     ~routes
     ~@body))

(defmacro with-fake-routes-in-isolation
  "Makes all wrapped clj-http requests first match against given routes.
  If no route matches, an exception is thrown.
  
  Thread local."
  [routes & body]
  `(impl/with-fake-routes-in-isolation
     set-fake-routes!
     ~routes
     ~@body))

(defmacro with-global-fake-routes-in-isolation
  "Like with-fake-routes-in-isolation, but visible to all threads."
  [routes & body]
  `(impl/with-global-fake-routes-in-isolation
     set-fake-routes!
     ~routes
     ~@body))

(defmacro with-global-fake-routes
  "Like with-fake-routes, but visible to all threads."
  [routes & body]
  `(impl/with-global-fake-routes
     set-fake-routes!
     ~routes
     ~@body))
