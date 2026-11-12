# Youth's Map frontend

Youth's Map is the Vietnamese React interface for Graph Routing System. The Java server includes the compiled UI in the standalone JAR. [Quick start](../README.md)

## Develop with Vite

Build the Java server and start it from the repository root:

```sh
mvn -Pjava21 clean verify
java -jar target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar serve --port 8080 --cors-origin http://localhost:5173
```

In a second terminal:

```sh
cd src/main/frontend
npm run dev
```

Open [Vite on localhost](http://localhost:5173). The proxy sends `/api` requests to Java at `http://127.0.0.1:8080`. If you change the backend address, set `BACKEND_TARGET` before starting Vite. Keep `changeOrigin: true` in `vite.config.js` so the backend receives a valid Host header. The browser's address must match an origin you passed to Java.

Use `npm run build` to produce `dist/` and `npm run preview` to preview that build on port 4173. Add `--cors-origin http://localhost:4173` to the backend command when using preview.

## Source layout

| Path under `src/main/frontend/` | Purpose |
| --- | --- |
| `src/App.jsx` | Switches between routing and analysis and retains the workspace state |
| `src/components/RoutingWorkspace.jsx` | Place search, route selection, directions, and traffic |
| `src/components/MapView.jsx` | MapLibre rendering, road labels, camera, and route overlays |
| `src/components/NetworkCanvas.jsx` | 2D fallback, pan and zoom, and large-graph rendering |
| `src/components/AnalysisWorkspace.jsx` | Algorithm comparison and trace playback |
| `src/components/GraphCanvas.jsx` | Small graphs and result highlights |
| `src/components/GraphEditPanel.jsx` | Custom graph editing |
| `src/hooks/useGraphWorkspace.js` | Analysis requests, graph loading, and revision-aware edits |
| `src/api.js` | API requests, cancellation, and error handling |
| `src/styles.css` and `src/styles/` | Design tokens, responsive layout, and component styles |

## Map and directions

The map uses road shapes and labels from the loaded OSM extract. The tilted view uses the same roads as the 2D view. When WebGL is unavailable, the app falls back to a 2D canvas. The location button requests the browser's location when you press it. The moving marker illustrates the route. Use the directions panel to move through individual steps.

The route list hides duplicate paths and keeps distinct alternatives. The UI groups consecutive instructions along the same named road and respects the system reduced-motion preference. Traffic edits replace the snapshot with a temporary observation for a selected arc.

## Tests

Run `npm test` from `src/main/frontend` for frontend unit and component tests. The packaged browser suite exercises desktop, tablet, and mobile layouts against the real Java API. [Verification commands](verification.md)

