# Envoy spike: Buster as an `ext_proc` processor

Question: can Buster sit behind a general-purpose proxy instead of inside the server, so it
reaches servers in any language?

Answer from this spike: yes. Envoy calls Buster over gRPC for every exchange; Buster's core
decides; Envoy sends the result to the caller.

```
caller --> Envoy :10000 --> your server :8081
              |  ^
   gRPC :9002 |  | mutated (or untouched) response
              v  |
        Buster processor
```

## Get started: everything in Docker

You need **Docker** with Compose. Nothing else: no JDK, no .NET SDK. Run from this folder
(`spikes/envoy`).

```
docker compose up --build -d      # builds and starts three containers; first build takes a few minutes
docker compose run --rm demo      # walks through what Buster does, in four steps
```

The three containers:

| Service | What | Port on your machine |
| --- | --- | --- |
| `garden` | the .NET service ([dotnet-upstream](dotnet-upstream/README.md)) | `8081` |
| `buster` | Buster as an Envoy processor, built from the repository ([Dockerfile.processor](Dockerfile.processor)) | none |
| `envoy` | the front door, asking Buster about every exchange | `10000` |

Try it by hand:

```
curl -i localhost:8081/beds/1     # the service itself: 200
curl -i localhost:10000/beds/1    # through Envoy and Buster: 503 problem+json, Retry-After: 1
```

Change what Buster does: edit or remove a script in `plugins/`, then
`docker compose restart buster`. With no plugin loaded, responses pass through unchanged.

Run the .NET service's tests: `docker compose run --rm tests`.

Stop: `docker compose down`.

The Buster image alone, to use in your own setup (build context is the repository root):

```
docker build -f spikes/envoy/Dockerfile.processor -t buster-processor .
docker run --rm -p 9002:9002 -v "$PWD/spikes/envoy/plugins":/plugins:ro buster-processor
```

Point Envoy's `ext_proc` filter at `:9002`; [envoy.compose.yaml](envoy.compose.yaml) shows how.

## Get started: pieces on the host

For working on the adapter itself. You need **Docker** and a **JDK 21** for the processor;
other JDK versions can fail under Gradle here, so point `JAVA_HOME` at a 21.

### 1. Start a server on :8081

Pick one.

**A .NET service** (the one to share; no .NET SDK needed):

```
docker run -d --name garden-dotnet -p 8081:8081 -e ASPNETCORE_URLS=http://+:8081 \
  -v "$PWD/dotnet-upstream":/src -w /src \
  mcr.microsoft.com/dotnet/sdk:10.0 dotnet run --project GardenUpstream
```

See [dotnet-upstream/README.md](dotnet-upstream/README.md). The first start downloads the
image and packages, so give it a minute: `curl localhost:8081/health` answers `ok` when ready.

**The built-in stand-in:** skip this step and omit `--no-upstream` in step 2.

### 2. Start the Buster processor

```
export JAVA_HOME=<a JDK 21>
./gradlew installDist
build/install/envoy-spike/bin/envoy-spike plugins --no-upstream &
```

`plugins` is the folder of plugin scripts to load. `--no-upstream` says something else
already answers on :8081.

### 3. Start Envoy

```
docker run -d --name buster-envoy -p 10000:10000 \
  -v "$PWD/envoy.yaml":/etc/envoy/envoy.yaml envoyproxy/envoy:v1.31-latest
```

### 4. Try it

```
curl -i localhost:8081/beds/1     # the server itself: 200
curl -i localhost:10000/beds/1    # through Envoy and Buster: 503 problem+json, Retry-After: 1
```

Empty the `plugins` folder (or point the processor at an empty one) and the same request
passes through unchanged.

### Stop

```
pkill -f envoy-spike/lib
docker rm -f buster-envoy garden-dotnet
```

## What is here

- `src/ExtProc.kt`: the gRPC adapter. One stream is one HTTP exchange. It builds a
  `BusterRequest` at request headers, and at the buffered response body hands request and
  response to `Engine.respond`. A mutated response goes back as an `ImmediateResponse`;
  an untouched one is answered CONTINUE.
- `src/Main.kt`: starts the processor on `:9002`, and a trivial JSON stand-in on `:8081`
  unless `--no-upstream` is given.
- `envoy.yaml`: Envoy on `:10000` in front of `:8081`, `ext_proc` asked for request headers,
  response headers and the whole response body (BUFFERED). It reaches the host through
  `host.docker.internal`. `envoy.compose.yaml` is the same, reaching `garden` and `buster`
  by compose service name.
- `compose.yaml`, `Dockerfile.processor`, `demo.sh`: the all-Docker run.
- `plugins/always-503.buster.kts`: a plugin that turns any success into a 503 problem.
- `dotnet-upstream/`: a .NET 10 service to put in front.

The core is not copied: `settings.gradle.kts` includes the repo root as a composite build.

## Observed

- With `always-503` loaded: upstream answers 200, caller gets `503 application/problem+json`
  with `retry-after: 1`. Envoy set `content-length`.
- With no plugins: the upstream response passes through unchanged.
- `host.docker.internal` resolved to an unreachable IPv6 address first, which made Envoy
  answer 500; `dns_lookup_family: V4_ONLY` fixes it.
- A HAL representation classifies as Layer RESOURCE and plain JSON as OTHER. A plugin that
  names only OTHER leaves HAL responses alone.

## Not done

- `/_buster/report`, `/_buster/events` and `/_buster/campaign` are not served: this
  adapter has no HTTP endpoint of its own. Envoy would route `/_buster/*` to a second
  listener in the processor's JVM.
- No campaign run through Envoy; the dry run, plan and rounds are core, so they should work,
  but this spike only shows a single mutation.
- Client identity here is `traceparent` or `User-Agent`; there is no remote address.
- 304 and HEAD (no body) take the `end_of_stream` path on response headers; not exercised.
- No streaming responses (SSE); body mode BUFFERED waits for the whole body.
- With no campaign running, the core mutates every response a plugin accepts.
