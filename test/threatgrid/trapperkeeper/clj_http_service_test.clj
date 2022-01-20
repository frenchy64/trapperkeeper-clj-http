(ns threatgrid.trapperkeeper.clj-http-service-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response]
            [ring.middleware.params :refer [wrap-params]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.app :as app]
            [threatgrid.trapperkeeper.clj-http-service :refer [clj-http-service]])
  (:import org.eclipse.jetty.server.Server))

(defn jetty-server-port
  "Return the port of a jetty Server instance."
  [^Server server]
  (some-> server .getURI .getPort))

(defn handler [_]
  (-> (response/response "hello")
      (response/content-type "text/html")))

(def jetty-app
  (-> handler wrap-params))

(deftest clj-http-service-test
  (let [server (jetty/run-jetty jetty-app {:port 0 :join? false})
        port (jetty-server-port server)
        url (format "http://localhost:%s" port)]
    (try
      (with-app-with-config app [clj-http-service] {}
        (let [{{:keys [core-request client-request set-fake-routes!]} :ThreatgridCljHttpService} (app/service-graph app)]
          (testing "set-fake-routes!"
            (is (thrown? clojure.lang.ExceptionInfo (set-fake-routes! {:fake-routes {} :in-isolation? true}))))
          (testing "core-request synchronous"
            (let [result (core-request {:request-method :get
                                        :uri "/"
                                        :scheme :http
                                        :server-name "localhost"
                                        :server-port port})]
              (is (= 200 (:status result)))
              (is (= "hello" (slurp (:body result))))))
          (testing "core-request async"
            (let [result (core-request {:request-method :get
                                        :uri "/"
                                        :scheme :http
                                        :server-name "localhost"
                                        :server-port port}
                                       identity
                                       #(throw %))]
              (is (= 200 (:status result)))
              (is (= "hello" (slurp (:body result))))))
          (testing "client-request synchronous"
            (let [result (client-request {;; only client/request supports :method
                                          :method :get
                                          :uri "/"
                                          :scheme :http
                                          :server-name "localhost"
                                          :server-port port})]
              (is (= 200 (:status result)))
              (is (= "hello" (:body result)))))
          (testing "client-request async"
            (let [result (client-request {;; only client/request supports :method
                                          :method :get
                                          :uri "/"
                                          :scheme :http
                                          :server-name "localhost"
                                          :server-port port}
                                         identity
                                         #(throw %))]
              (is (= 200 (:status result)))
              (is (= "hello" (slurp (:body result))))))))
      (finally
        (.stop server)))))
