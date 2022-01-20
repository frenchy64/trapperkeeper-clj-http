(ns threatgrid.trapperkeeper.clj-http-fake-service
  "Use this service in tests.

  Requires optional dependencies:
  - puppetlabs/trapperkeeper
  - org.clojure/math.combinatorics
  - ring/ring-codec
  - clj-http/clj-http"
  (:require clj-http.core
            clj-http.client
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [threatgrid.clj-http-fake :as fake]
            [threatgrid.trapperkeeper.clj-http-service.protocols :refer [core-request ThreatgridCljHttpService]]))

(tk/defservice clj-http-fake-service
  ThreatgridCljHttpService
  []
  (init [this _context] (let [fake-routes-atom (atom {})]
                          {::core-request-fn (partial fake/try-intercept clj-http.core/request #(deref fake-routes-atom))
                           ::fake-routes-atom fake-routes-atom}))
  (set-fake-routes! [this config] (-> this service-context ::fake-routes-atom (swap-vals! (constantly config))))
  (core-request [this request] ((-> this service-context ::core-request-fn) request))
  (core-request [this request respond raise] ((-> this service-context ::core-request-fn) request respond raise))
  (client-request [this request] ((clj-http.client/wrap-request (partial core-request this)) request))
  (client-request [this request respond raise] ((clj-http.client/wrap-request (partial core-request this)) request respond raise))) 

(defn ^:no-doc -app->set-fake-routes!
  "Internal"
  [app]
  (or (get-in (app/service-graph app) [:ThreatgridCljHttpService :set-fake-routes!])
      (throw (ex-info ":ThreatgridCljHttpService :set-fake-routes! is missing" {}))))

;; Public API

(defmacro with-fake-routes
  "Makes all wrapped clj-http requests first match against given routes.
  The actual HTTP request will be sent only if no matches are found.
  
  Like clj-http.fake/with-fake-routes, except
  takes an app, and requires the use of CljHttpClientService.

  Local to the current thread."
  [app routes & body]
  `(fake/with-fake-routes
     (-app->set-fake-routes! ~app)
     ~routes
     ~@body))

(defmacro with-fake-routes-in-isolation
  "Makes all wrapped clj-http requests first match against given routes.
  If no route matches, an exception is thrown.
  
  Like clj-http.fake/with-fake-routes-in-isolation, except
  takes an app, and requires the use of CljHttpClientService.

  Local to the current thread."
  [app routes & body]
  `(fake/with-fake-routes-in-isolation
     (-app->set-fake-routes! ~app)
     ~routes
     ~@body))

(defmacro with-global-fake-routes-in-isolation
  "Like clj-http.fake/with-global-fake-routes-in-isolation, except
  takes an app, requires the use of CljHttpClientService, and is app-local.
  Different apps calling this function may be interleaved without interference
  (but only one should probably be in effect per app at any one time)."
  [app routes & body]
  `(fake/with-global-fake-routes-in-isolation
     (-app->set-fake-routes! ~app)
     ~routes
     ~@body))

(defmacro with-global-fake-routes
  "Like clj-http.fake/with-global-fake-routes, except
  takes an app, requires the use of CljHttpClientService, and is app-local.
  Different apps calling this function may be interleaved without interference
  (but only one should probably be in effect per app at any one time)."
  [app routes & body]
  `(fake/with-global-fake-routes
     (-app->set-fake-routes! ~app)
     ~routes
     ~@body))
