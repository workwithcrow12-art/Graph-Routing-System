# Verification and publishing

Use a fresh checkout to verify the same inputs a new contributor will build. Keep the dependency lockfile and the OSM metadata sidecar with the source.

## Local checks

```sh
mvn -Pjava21 clean verify
npm --prefix src/main/frontend exec -- playwright install chromium
npm --prefix src/main/frontend run test:e2e:packaged
```

On supported Linux distributions, use `playwright install --with-deps chromium` when system libraries are missing. To use an installed Chromium browser, set `PLAYWRIGHT_CHROMIUM_EXECUTABLE` to its executable. Playwright starts and stops the packaged server on an available loopback port.

On Windows, run these checks after building the JAR:

```powershell
.\src\test\powershell\run-backend.test.ps1
.\src\test\powershell\package-source.test.ps1
```

They exercise the launcher with Unicode paths and check source archive contents. The Java suite verifies the bundled OSM checksum and the embedded UI and license files.

## Benchmarks

From the repository root after the build, run:

```sh
java -cp target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar graphroute.benchmark.RoutingBenchmark 100 20
java -cp target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar graphroute.benchmark.DatasetBenchmark 100
```

The benchmark reports separate index preparation and query times. It also reports work counts and path cost comparisons. Timing varies by machine and is not a test threshold.

## GitHub Actions

The workflow builds with Node.js 24 and checks Windows and Linux on JDK 21 and 25. It also checks macOS on JDK 21. JDK 21 jobs run Chromium against the standalone JAR. Windows jobs exercise the PowerShell launcher and source packager. GitHub uploads test and benchmark reports and the build artifacts. [Workflow](../.github/workflows/backend.yml)

## Publish the source

Include the MIT `LICENSE`, OSM extract and metadata, Maven and npm manifests, tests, and the GitHub workflow. The source ZIPs created by Maven and `package-source.ps1` contain these inputs. Git ignores build outputs and local caches. When creating a GitHub Release, use a freshly verified JAR so the embedded application and third-party license notices match the source. [Project license](../LICENSE)

