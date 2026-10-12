# Envoy spike: Buster as an `ext_proc` processor

Question: can Buster sit behind a general-purpose proxy instead of inside the server, so it
reaches servers in any language?

Answer from this spike: yes. Envoy calls Buster over gRPC for every exchange; Buster's core
decides; Envoy sends the result to the caller.

## What is here

- `src/ExtProc.kt`: the gRPC adapter. One stream is one HTTP exchange. It builds a
  `BusterRequest` at request headers, and at the buffered response body hands request and
  response to `Engine.respond`. A mutated response goes back as an `ImmediateResponse`;
  an untouched one is answered CONTINUE.
- `src/Main.kt`: starts a trivial JSON upstream on `:8081` and the processor on `:9002`.
- `envoy.yaml`: Envoy on `:10000` in front of the upstream, `ext_proc` asked for request
  headers, response headers and the whole response body (BUFFERED).
- `plugins/always-503.buster.kts`: a plugin that turns any success into a 503 problem.

The core is not copied: `settings.gradle.kts` includes the repo root as a composite build.

## Run it

```
export JAVA_HOME=<a JDK 21>
./gradlew installDist
build/install/envoy-spike/bin/envoy-spike plugins &
docker run -d --name buster-envoy -p 10000:10000 \
  -v $PWD/envoy.yaml:/etc/envoy/envoy.yaml envoyproxy/envoy:v1.31-latest
curl -i http://localhost:10000/beds/1
```

## Observed

- With `always-503` loaded: upstream answers 200 JSON, caller gets
  `503 application/problem+json` with `retry-after: 1`. Envoy set `content-length`.
- With no plugins: the upstream response passes through unchanged.
- `host.docker.internal` resolved to an unreachable IPv6 address first, which made Envoy
  answer 500; `dns_lookup_family: V4_ONLY` fixes it.

## Not done

- `/_buster/report`, `/_buster/events` and `/_buster/campaign` are not served: this
  adapter has no HTTP endpoint of its own. Envoy would route `/_buster/*` to a second
  listener in the processor's JVM.
- No campaign run through Envoy; the dry run, plan and rounds are core, so they should work,
  but this spike only shows a single mutation.
- Client identity here is `traceparent` or `User-Agent`; there is no remote address.
- 304 and HEAD (no body) take the `end_of_stream` path on response headers; not exercised.
- No streaming responses (SSE); body mode BUFFERED waits for the whole body.
- The fixed seed and "mutate every response" come from the core with no campaign running.
