#!/bin/sh
# Runs inside the compose network: `docker compose run --rm demo`.
# `garden` is the .NET service; `envoy` is the front door with Buster behind it.

# The processor and the service take a moment to be ready.
# Envoy answers 500 until the processor is up.
for i in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://envoy:10000/health)
  [ "$code" != "500" ] && [ "$code" != "000" ] && break
  sleep 1
done

echo "== 1. The .NET service itself: a bed"
curl -si http://garden:8081/beds/1 | head -n 1
echo
echo "== 2. The same request through Envoy and Buster"
curl -si http://envoy:10000/beds/1 | grep -i -E "^HTTP|content-type|retry-after"
echo
echo "== 3. Harvest bed 2 through Envoy: the caller is told it failed"
curl -si -X POST http://envoy:10000/beds/2/harvest | head -n 1
echo
echo "== 4. Ask the service directly: the harvest happened anyway"
curl -s http://garden:8081/beds/2
echo
echo
echo "A client that retries after the 503 harvests twice. Buster is how you find out which"
echo "clients do. Edit a plugin in spikes/envoy/plugins and run 'docker compose restart buster'."
