# The Strangler-Fig Proxy

This is the **Camel strangler-fig proxy** (ch.14 "The Strangler Fig Pattern",
r02-plan step S7, build-plan.md §E step 0) — a standalone Camel-on-Quarkus
application that sits in front of the Spring Boot monolith
(`examples/00-monolith/`) so that bounded contexts can be peeled off onto
Quarkus **one seam at a time**, with clients none the wiser about which
backend actually served a given request.

## The pattern

Martin Fowler's strangler-fig application grows a new system up around the
edges of an old one until the old one can be removed. The mechanical piece
that makes this safe is a **facade in front of both systems** that decides,
per request, which one answers. This project *is* that facade for Review, the
first extraction in the decomposition roadmap (build-plan.md §E):

```
            ┌──────────────────┐
 client --> │  strangler proxy │ --> monolith            (default, r02)
            │     :8888        │ --> review-service :8081 (once cut over, S10)
            └──────────────────┘
```

Every request to the proxy is reverse-proxied **as-is**: same HTTP method,
path, query string, headers, and body go out; the backend's actual status
code and body come straight back. The proxy introduces zero observable
difference versus talking to a backend directly — that transparency is the
whole point, and it's what the behavior-equivalence suite proves (see
"Verifying transparency" below).

## The route

One route, one `RouteBuilder`
(`src/main/java/dev/patterncatalyst/strangler/StranglerProxyRoute.java`):

1. **Consume** `platform-http:/api?matchOnUriPrefix=true` — every request
   under `/api/**` lands here.
2. **Content-based routing on the Review seam** — a `choice()` checks whether
   the request path starts with `/reviews` *and* the cutover flag is on; every
   other path (and `/reviews` with the flag off) is routed to the monolith.
3. **Reverse-proxy** to whichever backend was chosen, via the Camel `http`
   producer with `bridgeEndpoint=true` (reuse the inbound method/path/query
   as-is) and `throwExceptionOnFailure=false` (pass the backend's real status
   code and body back instead of raising a Camel exception on 4xx/5xx).

## The flag: `strangler.review.enabled`

| Value | Backend for `/api/reviews/**` | Backend for everything else |
|---|---|---|
| `false` (**default**, r02/S7) | monolith `:8080` | monolith `:8080` |
| `true` (from S10) | Review service `:8081` | monolith `:8080` |

Set in `src/main/resources/application.properties`, or overridden at runtime
with `-Dstrangler.review.enabled=true` / `STRANGLER_REVIEW_ENABLED=true`. It
is read once per request from Quarkus/SmallRye Config — flipping it is a
config change plus a restart, not a code change, which is exactly what makes
the eventual cutover (and rolling it back) a *reversible* operation rather
than a rewrite.

Two other properties name the fixed backend targets the flag chooses between.
The route never builds a target URI from request data — only ever from these
two configured constants — which keeps the dynamic-URI seam secure by
default (no header or path value can redirect the proxy to an arbitrary
host):

```properties
strangler.monolith.base-url=http://localhost:8080
strangler.review.base-url=http://localhost:8081
```

## Ports

| Component | Port | Note |
|---|---|---|
| Strangler proxy (this project) | **8888** | `quarkus.http.port` |
| Monolith (`examples/00-monolith/`) | 8080 | default target, always |
| Review service (`examples/15-review-service/`, arrives S8+) | 8081 | only reachable through the proxy once the flag flips (S10) |

## Running it

```bash
# 1. podman stack + monolith already up (see tooling/newman/README.md)
# 2. build and run the proxy
cd examples/01-strangler-proxy
mvn -q -DskipTests package
java -jar target/quarkus-app/quarkus-run.jar
# proxy now listening on :8888, flag defaults to false (-> monolith)
```

Or for the dev-mode inner loop: `quarkus dev` (live reload on route changes).

## Verifying transparency (the equivalence gate, through the proxy)

The project's behavior-equivalence suite
(`tooling/newman/mea.postman_collection.json`) is baseUrl-parameterized, so
the exact same 49 assertions that pass against the monolith directly must
also pass **through this proxy**, proving it is a transparent reverse proxy
today (flag off, everything still reaches the monolith):

```bash
demos/demo-equivalence.sh http://localhost:8888
```

A green run here, with the collection completely unmodified, is the proxy's
definition of done for r02/S7 (build-plan.md §E step 0 and §G's load-bearing
rule). When S10 flips `strangler.review.enabled=true` and
`examples/15-review-service/` exists, the same command re-run against
`:8888` must *still* be green — only now the Review folder of the collection
is actually being served by the extracted Quarkus service instead of the
monolith, with no client-visible difference.
