# JSON API

Start the Java server from the repository root. It listens on `127.0.0.1:8080` by default and serves the UI and API on that port. [Quick start](../README.md)

## Endpoints

| Method | Endpoint | Response or action |
| --- | --- | --- |
| GET | `/` | React UI for clients that send `Accept: text/html`. Other clients receive JSON discovery. |
| GET | `/api` | Endpoint discovery as JSON |
| GET | `/api/health` | Dataset, node and arc counts, provenance, and traffic status |
| GET | `/api/map` | Nodes, arcs, and road geometry, with an optional `bbox` filter |
| GET | `/api/places?q=nguyen%20trai&limit=10` | Matching places with pagination |
| GET | `/api/nearest?lat=20.99351&lng=105.80436&maxDistanceMeters=2000` | Nearest eligible node |
| POST | `/api/routes` | Route, alternatives, and directions |
| GET | `/api/algorithms` | Algorithm descriptions |
| GET | `/api/analysis/graphs` | Datasets and algorithm availability |
| GET | `/api/analysis/graph?graph=sample` | Nodes, edges, and revision for the selected graph |
| POST | `/api/algorithms/run` | Algorithm result and trace |
| POST | `/api/analysis/graph/edit` | Edit or reset the `custom` graph |
| GET | `/api/traffic` | Current traffic snapshot status |
| POST | `/api/traffic/update` | Replace the traffic snapshot |

## Find a place or route

Search matches names, IDs, and adjacent street names without requiring exact capitalization or Vietnamese accents. Supply a nonblank `q`. The response includes `places`, `total`, and `offset`. Set `limit` to 1–100 and `offset` to a nonnegative integer.

Place search and nearest-node lookup default to `driving`. You can also select `motorcycling`, `cycling`, `walking`, or `all`. Mode-specific searches return nodes on eligible public arcs. Each place includes `modes`, `mainComponentModes`, and a `displayName` that helps distinguish similar names. A search hit may be on a disconnected part of the network.

Nearest-node lookup defaults to a 2 km radius and accepts at most 100 km. It prefers the largest strongly connected component for the mode. If the mode has only single-node components, it considers all public endpoints. Use `all` to search every node.

Get valid endpoints from `/api/places` or `/api/nearest` before sending `POST /api/routes`. This PowerShell example uses the endpoints returned by the CLI demo:

```powershell
$demo = .\run-backend.ps1 demo | ConvertFrom-Json
$path = $demo.routes[0].path
$body = @{
    origin = $path[0].id
    destination = $path[-1].id
    mode = 'driving'
    preference = 'fastest'
    alternatives = 3
} | ConvertTo-Json
Invoke-RestMethod 'http://127.0.0.1:8080/api/routes' -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

| Field | Accepted values |
| --- | --- |
| `origin`, `destination` | Required node IDs |
| `mode` | `driving` by default, `motorcycling`, `cycling`, or `walking` |
| `preference` | `shortest` by default, measured in kilometers, or `fastest`, measured in seconds |
| `algorithm` | `astar` by default or `dijkstra` |
| `alternatives` | 1–3 routes including the primary route. Fewer can be available. |
| `waypoints` | Up to eight ordered node IDs |
| `avoidRoads` | Up to 50 complete street names. Matching ignores case and accents. An unknown street name produces an error. |
| `authorizedRestrictedWays` | Up to 50 OSM way IDs for roads tagged `destination`, `delivery`, or `customers`. Supply an ID only when you have access and a stop on the way. |

A listed restricted way is eligible only for a leg whose start or end is on that way. `private` and `no` remain excluded. The CLI takes `--authorize-way WAY_ID`.

Each route includes distance, duration, node path, directions, GeoJSON geometry, and `sharedDistanceRatio`. Route coordinates use `[longitude, latitude]`. Road geometry from `/api/map` uses objects with `latitude` and `longitude`. The server groups consecutive continue instructions on the same named road and rounds durations so their sum matches the route duration.

Alternatives are distinct by directed arc sequence and sorted by cost. The response includes `segments` and `sameNodeSequenceAsPrimary` so clients can distinguish parallel ways through the same nodes. Routes with waypoints can revisit nodes across legs. Arc IDs apply to the currently loaded network.

Each request shares a budget across all legs of 2,000,000 work units, 2,048 spur searches, 16 MiB of estimated path and geometry allocations, and five seconds. HTTP 422 with `ROUTE_LIMIT_EXCEEDED` means the search exceeded a budget. Retry with `alternatives: 1` or split the trip.

## Analyze a graph

`GET /api/analysis/graphs` describes the `loaded`, `sample`, and `custom` datasets. Check the algorithm's `available` flag and explanation before running it. Dijkstra, A*, Bellman-Ford, and Floyd-Warshall take `startId` and `goalId`. BFS and DFS support traversal or a path search. Prim and Kruskal run on an undirected graph and return a minimum spanning forest.

```json
{"algorithm":"prim","graph":"sample","maxTraceSteps":100}
```

The default graph is `loaded`. Use `nodeIds` to select an induced subgraph of 1–300 unique nodes. Set `undirected: true` to project a directed graph for minimum spanning analysis. This projection keeps the lowest-weight connection for each node pair. Routing continues to use the original road directions.

Traces contain up to 1,000 events by default and accept a limit of 0–10,000. Metrics report the total event count and whether the trace was truncated. Analysis uses the stored edge weights. General analysis limits are 100,000 nodes and 500,000 arcs. Floyd-Warshall accepts up to 300 nodes and Bellman-Ford applies an estimated 25 million relaxation limit. Analysis Dijkstra and A* have a separate resource budget. The HTTP deadline still applies.

To edit `custom`, fetch its current `revision` and send it as `expectedRevision`:

```json
{"operation":"addNode","expectedRevision":0,"node":{"id":"new","name":"New node","latitude":21.0,"longitude":105.8}}
```

Operations are `addNode`, `removeNode`, `addEdge`, `removeEdge`, `removeEdges`, and `reset`. Supply `nodeId` when removing a node. Edge edits use `edge.source`, `edge.target`, and, where applicable, `edge.weight` in kilometers and `edge.roadName`. For a single edge, include enough fields to distinguish parallel roads. Use `removeEdges` for bulk removal. Successful edits increment the revision. A stale revision returns HTTP 409 without modifying the graph. The custom graph supports up to 300 nodes and 2,000 arcs and resets when the server restarts.

## Traffic observations

A traffic update replaces the whole snapshot. Enter data yourself or load a file with `--traffic PATH`. The server does not fetch a live feed.

```powershell
$map = Invoke-RestMethod 'http://127.0.0.1:8080/api/map'
$road = $map.roads | Where-Object { $_.speedsKph.DRIVING } | Select-Object -First 1
$now = [DateTimeOffset]::UtcNow
$body = @{
    source = 'local test observation'
    observedAt = $now.ToString('o')
    expiresAt = $now.AddMinutes(10).ToString('o')
    updates = @(@{
        from = $road.source
        to = $road.target
        sourceWayId = $road.sourceWayId
        segmentId = $road.id
        mode = 'driving'
        speedKph = 8
        closed = $false
    })
} | ConvertTo-Json -Depth 5
Invoke-RestMethod 'http://127.0.0.1:8080/api/traffic/update' -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

Use ISO 8601 timestamps and an observation lifetime of at most 24 hours. A closure uses `closed: true`. Other updates require `speedKph` greater than zero and at most 200. Effective speed cannot exceed the base speed for the arc. The server rejects stale observations, invalid targets, and overlapping updates. Expired observations stop affecting routes. `updates: []` clears observations and retains the update time so delayed data cannot supersede it.

A `segmentId` targets one directed arc and must agree with `from`, `to`, `mode`, and any supplied `sourceWayId`. Without a segment ID, the legacy selector applies to all arcs matching the supplied endpoints, mode, and optional way ID. Avoid overlapping group and single-arc selectors. Refresh segment IDs after changing or restarting the loaded network.

## Map bounds and HTTP behavior

`GET /api/map?bbox=20.993,105.803,20.995,105.806` selects arcs whose geometry bounds intersect the requested viewport, including roads crossing it with both endpoints outside. Coordinates use `south,west,north,east`. Values must be finite and ordered, and the filter does not wrap the antimeridian. Geometry stays intact and `bounds` describes the full dataset. Without `bbox`, the response contains the cached full map.

POST requests require `Content-Type: application/json`. Bodies are limited to 16 KiB, except `/api/traffic/update`, which accepts up to 8 MiB and 10,000 updates. The server rejects unknown fields, duplicate keys, trailing JSON, incorrect scalar types, and unsupported query parameters.

The server validates `Host` against `127.0.0.1` or `localhost` and its actual port. Browser requests with `Origin` must use the same origin or match a configured `--cors-origin`. Pass that option once per development origin. Vite uses `changeOrigin: true` so requests through the proxy have the backend's host. CORS preflight accepts the endpoint's method and `Content-Type` and returns HTTP 204 when valid. [Vite proxy documentation](https://vite.dev/config/server-options#server-proxy)

GET endpoints also support HEAD. HEAD returns the same status and headers without a body. POST-only endpoints return 405 for HEAD. Responses of at least 1 KiB support gzip when requested. `gzip;q=0` disables gzip. The server includes `Origin` and `Accept-Encoding` in `Vary` where applicable.

The Java server exposes the packaged index and flat Vite asset paths. If the frontend is missing, browser navigation returns `FRONTEND_NOT_BUILT` with HTTP 503. JSON endpoints remain available.

The server uses 2–8 HTTP workers and a queue of up to 32 tasks. A 15-second deadline includes queue time. A saturated queue or an expired request can close the connection. JSON responses are limited to 16 MiB before compression.

Errors use `{"error":{"code":"NO_ROUTE","message":"..."}}`. Typical statuses are 400 for invalid input, 403 for a rejected Host or Origin, 404 for a missing resource, 405 for an unsupported method, 409 for revision conflicts, 413 for large bodies, 415 for content type, and 422 for routing, analysis, or response limits. The JDK HTTP parser can return an HTML 400 for malformed URIs before the request reaches the application.

