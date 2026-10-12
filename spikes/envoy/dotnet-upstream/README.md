# GardenUpstream: a .NET service to put Buster in front of

A small ASP.NET Core (.NET 10, Minimal API) service that serves garden beds as HAL
representations. It is deliberately honest and not flaky. Buster supplies the faults.

## The resources

| Request | Answer |
| --- | --- |
| `GET /beds/{id}` | `200 application/hal+json`, an `ETag`, `Cache-Control: private, no-cache`, and links `self` and `harvest`. `304` when `If-None-Match` is current. |
| `POST /beds/{id}/harvest` | `200` with the bed, a new `ETag`, and `Content-Location: /beds/{id}`. `412` when `If-Match` is stale. |
| `GET /health` | `200 ok` |

State is a harvest count per bed, in memory. The count makes the ETag.

## Quickest: the whole demo in Docker

You need Docker with Compose, and nothing else. From `spikes/envoy` (the folder above):

```
docker compose up --build -d      # this service, Buster, and Envoy; first build takes a few minutes
docker compose run --rm demo      # four steps showing what Buster does to it
docker compose down
```

The service is on `localhost:8081`; through Buster it is on `localhost:10000`.
The rest of this page is the service on its own.

## Get started: the service on its own

You need Docker. You do not need the .NET SDK. Run from this folder
(`spikes/envoy/dotnet-upstream`).

**Run the service on :8081**

```
docker run -d --name garden-dotnet -p 8081:8081 -e ASPNETCORE_URLS=http://+:8081 \
  -v "$PWD":/src -w /src mcr.microsoft.com/dotnet/sdk:10.0 dotnet run --project GardenUpstream
```

The first start downloads the image and packages. It is ready when this answers `ok`:

```
curl localhost:8081/health
curl -i localhost:8081/beds/1
```

**Run the tests**

```
docker run --rm -v "$PWD":/src -w /src mcr.microsoft.com/dotnet/sdk:10.0 dotnet test GardenUpstream.Tests
```

**With the .NET SDK installed**, the same without Docker:

```
dotnet run --project GardenUpstream --urls http://localhost:8081
dotnet test GardenUpstream.Tests
```

**Stop**

```
docker rm -f garden-dotnet
```

## Put Buster in front of it

Buster is a JVM program, and the service needs to know nothing about it. Envoy sits in
front of the service and asks Buster, over gRPC, whether to change each response.

The easy way is `docker compose up --build -d` in `spikes/envoy`, as at the top of this page.
To run the pieces on the host instead, start the service as above, then follow steps 2 to 4
in [../README.md](../README.md#get-started-pieces-on-the-host): start the Buster processor
with `--no-upstream`, start Envoy, then:

```
curl -i localhost:8081/beds/1     # the service itself, unchanged
curl -i localhost:10000/beds/1    # through Envoy and Buster: 503 problem+json, Retry-After: 1
```

## What to look at

- `POST http://localhost:10000/beds/1/harvest` answers `503`, yet the harvest happened:
  `GET http://localhost:8081/beds/1` shows `"harvests":1`. A client that treats the 503 as
  "nothing happened" and retries the act harvests twice. That is the kind of mistake Buster
  exists to catch, and `If-Match` is how a well-behaved client avoids it.
- The `always-503` plugin names both `Layer.RESOURCE` and `Layer.OTHER`. A HAL
  representation classifies as RESOURCE; plain JSON as OTHER.
