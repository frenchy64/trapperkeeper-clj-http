(ns threatgrid.trapperkeeper.clj-http-service
  "Use this service in production.

  Requires optional dependencies:
  - puppetlabs/trapperkeeper
  - clj-http/clj-http"
  (:require clj-http.client
            clj-http.core
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [threatgrid.trapperkeeper.clj-http-service.protocols :refer [ThreatgridCljHttpService]]))

(tk/defservice clj-http-service
  ThreatgridCljHttpService
  []
  (set-fake-routes! [this config] (throw (ex-info "Dev only!!" {})))
  (core-request [this request] (clj-http.core/request request))
  (core-request [this request respond raise] (clj-http.core/request request respond raise))
  (client-request [this request] (clj-http.client/request request))
  (client-request [this request respond raise] (clj-http.client/request request respond raise)))
