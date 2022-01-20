(ns threatgrid.trapperkeeper.clj-http-fake-service-test
  (:require [clojure.test :refer [deftest is testing]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.app :as app]
            [threatgrid.trapperkeeper.clj-http-fake-service :as fake :refer [clj-http-fake-service]])
  (:import org.eclipse.jetty.server.Server))

(defn maybe-slurp [v]
  (cond-> v
    (not (string? v)) slurp))

;; TODO test all combinations of with-fake-* macros and service methods
(deftest clj-http-fake-service-test
  (with-app-with-config app [clj-http-fake-service] {}
    (let [{{:keys [core-request client-request set-fake-routes!]} :ThreatgridCljHttpService} (app/service-graph app)
          port (rand-int 9000)]
      (testing "core-request synchronous"
        (let [unique (str (gensym))
              result (fake/with-global-fake-routes-in-isolation app
                       {(format "http://localhost:%s/" port)
                        (fn [request]
                          {:status 200 :headers {} :body unique})}
                       (core-request {:request-method :get
                                      :uri "/"
                                      :scheme :http
                                      :server-name "localhost"
                                      :server-port port}))]
          (is (= 200 (:status result)))
          (is (= unique (maybe-slurp (:body result))))))
      (testing "core-request async"
        (let [unique (str (gensym))
              result (fake/with-global-fake-routes-in-isolation app
                       {(format "http://localhost:%s/" port)
                        (fn [request]
                          {:status 200 :headers {} :body unique})}
                       (core-request {:request-method :get
                                      :uri "/"
                                      :scheme :http
                                      :server-name "localhost"
                                      :server-port port}
                                     identity
                                     #(throw %)))]
          (is (= 200 (:status result)))
          (is (= unique (maybe-slurp (:body result))))))
      (testing "client-request synchronous"
        (let [unique (str (gensym))
              result (fake/with-global-fake-routes-in-isolation app
                       {(format "http://localhost:%s/" port)
                        (fn [request]
                          {:status 200 :headers {} :body unique})}
                       (client-request {;; only client/request supports :method
                                        :method :get
                                        :uri "/"
                                        :scheme :http
                                        :server-name "localhost"
                                        :server-port port}))]
          (is (= 200 (:status result)))
          (is (= unique (:body result)))))
      (testing "client-request async"
        (let [unique (str (gensym))
              result (fake/with-global-fake-routes-in-isolation app
                       {(format "http://localhost:%s/" port)
                        (fn [request]
                          {:status 200 :headers {} :body unique})}
                       (client-request {;; only client/request supports :method
                                        :method :get
                                        :uri "/"
                                        :scheme :http
                                        :server-name "localhost"
                                        :server-port port}
                                       identity
                                       #(throw %)))]
          (is (= 200 (:status result)))
          (is (= unique (maybe-slurp (:body result)))))))))
