# trapperkeeper-clj-http

A trapperkeeper service with implementations for clj-http and clj-http-fake.

Sufficient implementation details are made public so you can make your own service for your favorite framework.

## Background

[clj-http](https://github.com/dakrone/clj-http) has a handy stubbing library [clj-http-fake](https://github.com/unrelentingtech/clj-http-fake),
but clj-http-fake uses global state via `with-redefs` (it also provides thread-local ops).

With a services framework like trapperkeeper (or component, mount, etc.,)
we can encapsulate that global fake route state per app, which opens the door for parallelism
in test suites.

## Usage

Note: This project has many optional dependencies that you must declare yourself in
your own project. See `project.clj` and each namespace for details.

First add this project as a dependency.

```clojure
;; leiningen
[org.clojars.threatgrid-clojars/trapperkeeper-clj-http "..."]
;; Clojure CLI
{org.clojars.threatgrid-clojars/trapperkeeper-clj-http {:mvn/version "..."}}
```

Now audit your codebase for any usages of `clj-http.client/request`. Decide which
services are the best place to "own" this usage, and add a dependency to `ThreatgridCljHttpService`.

```clojure
(tk/defservice foo
  Foo
  [[:ThreatgridCljHttpService client-request]]
  ...)
```

Now replace all calls to `clj-http.client/request` with the injected value of `client-request`.
If you don't have any calls yet, then you're ahead of the game--just start using `client-request`
instead of `clj-http.client/request`.

In production, you want `client-request` to be exactly `clj-http.client/request`. This is exactly
what `clj-http-service` does, so add the following to your production bootstrap.cfg:

```cfg
# production
threatgrid.trapperkeeper.clj-http-service/clj-http-service
```

During testing, use the following service to enable fake routes.

```cfg
# testing
threatgrid.trapperkeeper.clj-http-fake-service/clj-http-fake-service
```

Now, you can use the macros in `threatgrid.trapperkeeper.clj-http-fake-service` to specify
fake routes on a per-app basis. Remember, they take an `app` as the first argument, and the
rest of the arguments are just like `clj-http.fake`.

See the tests in `threatgrid.trapperkeeper.clj-http-fake-service-test` for examples
of specifying fake routes.

## Development

See the `deploy` step of the Actions workflow for how to deploy. Ensure the required secrets
are in place.

## License

Copyright and license is the same as https://github.com/unrelentingtech/clj-http-fake.

Released under [the MIT License](http://www.opensource.org/licenses/mit-license.php).
