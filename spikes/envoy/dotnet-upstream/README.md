# GardenUpstream: a .NET service to put Buster in front of

A small ASP.NET Core (.NET 8, Minimal API) service that serves garden beds as HAL
representations. It is deliberately honest and not flaky. Buster supplies the faults.

## The resources

| Request | Answer |
| --- | --- |
| `GET /beds/{id}` | `200 application/hal+json`, an `ETag`, `Cache-Control: private, no-cache`, and links `self` and `harvest`. `304` when `If-None-Match` is current. |
| `POST /beds/{id}/harvest` | `200` with the bed, a new `ETag`, and `Content-Location: /beds/{id}`. `412` when `If-Match` is stale. |
| `GET /health` | `200 ok` |

State is a harvest count per bed, in memory. The count makes the ETag.

## Run it

With the .NET 8 SDK:

```
dotnet run --project GardenUpstream --urls http://localhost:8081
dotnet test GardenUpstream.Tests
```

Without it, in Docker:

```
docker run --rm -p 8081:8081 -e ASPNETCORE_URLS=http://+:8081 -v "$PWD":/src -w /src \
  mcr.microsoft.com/dotnet/sdk:8.0 dotnet run --project GardenUpstream
```

## Put Buster in front of it

Buster is a JVM program, and the service needs to know nothing about it. Envoy sits in
front of the service and asks Buster, over gRPC, whether to change each response. From
`spikes/envoy`:

```
./gradlew installDist
build/install/envoy-spike/bin/envoy-spike plugins --no-upstream &
docker run -d --name buster-envoy -p 10000:10000 \
  -v $PWD/envoy.yaml:/etc/envoy/envoy.yaml envoyproxy/envoy:v1.31-latest

curl -i http://localhost:10000/beds/1            # 503 problem+json, Retry-After: 1
curl -i http://localhost:8081/beds/1             # the service itself, unchanged
```

`--no-upstream` tells the processor not to start its own stand-in service on `:8081`.
Envoy reaches the service and the processor through `host.docker.internal`.

## What to look at

- `POST http://localhost:10000/beds/1/harvest` answers `503`, yet the harvest happened:
  `GET http://localhost:8081/beds/1` shows `"harvests":1`. A client that treats the 503 as
  "nothing happened" and retries the act harvests twice. That is the kind of mistake Buster
  exists to catch, and `If-Match` is how a well-behaved client avoids it.
- The `always-503` plugin names both `Layer.RESOURCE` and `Layer.OTHER`. A HAL
  representation classifies as RESOURCE; plain JSON as OTHER.
