(ns threatgrid.trapperkeeper.clj-http-service.protocols)

(defprotocol ThreatgridCljHttpService
  (core-request [this request] [this request respond raise] "Returns a function to stub for clj-http.core/request.")
  (client-request [this request] [this request respond raise] "Returns a function to stub for clj-http.client/request.")
  (set-fake-routes! [this config] "Set a global fake routes config. Maybe overriden by non-nil *local-config*.
                                  Result must be [old-config new-config], like clojure.core/swap-vals!."))
