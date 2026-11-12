# Graph Routing System

Graph Routing System is a local route planner and graph algorithm visualizer for Thanh Xuân, Hanoi. Its Vietnamese interface, Youth's Map, uses React and MapLibre. The Java server ships with an OpenStreetMap extract and serves the map and JSON API from one port.

- Search by place or street name with or without Vietnamese accents. You can also pick points on the map or enter coordinates.
- Plan the shortest or fastest route by car, motorcycle, bicycle, or on foot. Compare up to three routes and step through directions.
- Compare graph algorithms, replay their traces, and edit a separate custom graph.
- Record a temporary speed or closure for one road segment and see how it affects routing.

## Quick start

To build from source, install JDK 21 or later, Maven 3.9+, and Node.js 22.12+ with npm. Set `JAVA_HOME` to the JDK and make sure `java -version` and `mvn -version` show the intended Java version. The first build downloads dependencies from Maven Central and the npm registry.

Run these commands from the repository root:

```sh
mvn -Pjava21 clean verify
java -jar target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar serve --port 8080
```

Open [Youth's Map](http://127.0.0.1:8080). Press Ctrl+C to stop the server. If port 8080 is busy, pass a different `--port` and open the corresponding address. Once you have the standalone JAR, you only need Java 21 or later to run this build.

Use `mvn clean verify` with JDK 25 or later to build the default Java 25 target. Both build profiles install the locked frontend dependencies, compile the UI, run the Java and frontend tests, and package the JAR.

## Command line and API

Run the CLI from the same directory as the build. On Windows, `.\run-backend.ps1` also accepts these commands and preserves Vietnamese arguments and file paths.

```sh
java -jar target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar demo
java -jar target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar search "nguyen trai"
java -jar target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar nearest 20.99351 105.80436
java -jar target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar analyze bfs --graph sample --trace-limit 100
java -jar target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar --help
```

Use the IDs returned by `search`, `nearest`, or `map` with `route ORIGIN_ID DESTINATION_ID --mode driving --preference fastest --alternatives 3`. Use the API or CLI for ordered waypoints and conditional road access. The browser UI can avoid roads by name.

`GET /api` lists the endpoints. `GET /api/health` reports the loaded dataset and server status. [JSON API reference](docs/api.md)

## Graph analysis

Open the analysis workspace to compare Dijkstra, A*, Bellman-Ford, Floyd-Warshall, BFS, DFS, Prim, and Kruskal. The interface lists which algorithms each dataset supports.

| Dataset | Use |
| --- | --- |
| `loaded` | Analyze the full directed road network or select up to 300 nodes. |
| `sample` | Try all eight algorithms on a small undirected graph. |
| `custom` | Add or remove nodes and edges in a separate graph held in memory. |

Use the trace controls to step through a result, replay it, or change playback speed. Analysis uses the graph's original edge weights. The routing screen applies the selected vehicle profile and current traffic observations.

## Road data

The bundled OpenStreetMap extract represents Thanh Xuân, Hanoi, as of **2026-10-19 10:55 UTC**. The importer builds **3,419 nodes** and **8,782 directed road segments**. The metadata beside the extract records its source, query bounds, timestamp, and SHA-256 checksum. Git preserves the extract byte for byte across checkouts.

The importer keeps OSM intersections and road shapes. It handles one-way streets, roundabouts, and supported vehicle access tags. It estimates travel times from road type, surface, and speed limits where available. A road without a name keeps an empty name.

To use your own extract, pass `--osm path/to/extract.osm` or `--osm path/to/extract.json` to `serve`. The importer accepts OSM XML and Overpass JSON with node coordinates or way geometry. For the existing node and edge JSON format, use `--nodes PATH --edges PATH`. To download a new local extract, run the following:

```sh
java -jar target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar update-osm --bbox 20.990,105.795,21.007,105.820 --output data/extract.json
```

The updater validates the extract before replacing the output. It reuses a matching local copy if the query and checksum agree. Use `--force` to refresh it or `--endpoint HTTPS_URL` to select an Overpass server. [Overpass documentation](https://wiki.openstreetmap.org/wiki/Overpass_API)

## Development and verification

Java source and tests live under `src/main/java` and `src/test/java`. React source lives in `src/main/frontend`. Use Vite to work on the UI with live reload. [Frontend development](docs/youthsmap-ui.md)

```sh
mvn -Pjava21 clean verify
npm --prefix src/main/frontend exec -- playwright install chromium
npm --prefix src/main/frontend run test:e2e:packaged
```

Playwright starts the standalone JAR on a temporary loopback port and stops it after the tests. On supported Linux distributions, add `--with-deps` to the Chromium installation command if you need the browser's system libraries. [Verification and CI](docs/verification.md)

The Maven build produces the standalone JAR and a source archive under `target/`. On Windows, you can also run `.\package-source.ps1` to create `target/GraphRoutingSystem-source.zip`. Source archives include the code, tests, data, documentation, and license. Build output, local caches, and installed dependencies are excluded.

## Scope and limitations

- The map covers the bundled or imported extract. Search matches places in that dataset. Coordinate lookup snaps to a nearby graph node.
- The map uses road geometry, including in its tilted view. It has a 2D canvas fallback. Online base tiles, buildings, and elevation are outside the current scope.
- Traffic comes from observations you enter. The application has no live traffic feed or public transit. Travel times are estimates.
- The importer covers a subset of OSM access rules. It does not model turn restrictions, node barriers, or time-dependent access. Check posted road restrictions before relying on a route.
- The server binds to `127.0.0.1` for local use. The project provides no user authentication for a public deployment.

## License

The project source code is available under the MIT License. Copyright (c) 2026 Dũng Nguyễn. [MIT license](LICENSE)

Road data is **© OpenStreetMap contributors** and is available under the Open Database License 1.0. The data notice also appears in the map and dataset metadata. [OpenStreetMap attribution](https://www.openstreetmap.org/copyright) · [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/)

Third-party libraries and the Inter font retain their own licenses. Both source archives include `LICENSE`. The frontend distribution includes `LICENSE` and `THIRD-PARTY-LICENSES.md`. The standalone JAR includes those files under `web/` and preserves Java dependency notices under `META-INF/licenses/java/`.
<<<<<<< HEAD

=======

>>>>>>> 2ac57e9eda84bff4714effeae6bd48f520eb88c3
