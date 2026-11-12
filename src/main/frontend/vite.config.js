import { existsSync, readdirSync, readFileSync } from "node:fs";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const proxy = {
  "/api": {
    target: process.env.BACKEND_TARGET || "http://127.0.0.1:8080",
    changeOrigin: true,
  },
};

function distributionLicenses() {
  return {
    name: "distribution-licenses",
    generateBundle() {
      const root = new URL("./", import.meta.url);
      const { packages } = JSON.parse(readFileSync(new URL("package-lock.json", root), "utf8"));
      const sections = ["# Third-party frontend licenses"];
      for (const [packagePath, details] of Object.entries(packages)) {
        if (!packagePath || details.dev) continue;
        const directory = new URL(`${packagePath}/`, root);
        if (details.optional && !existsSync(directory)) continue;

        const name = packagePath.split("node_modules/").at(-1);
        const noticeFiles = readdirSync(directory, { withFileTypes: true })
          .filter((entry) => entry.isFile() && /^(licen[cs]e|copying|notice|ofl|unlicense)([-._].*)?$/i.test(entry.name))
          .map((entry) => entry.name)
          .sort();
        const notices = noticeFiles.map((file) => readFileSync(new URL(file, directory), "utf8").trimEnd());
        // The published murmurhash-js package puts its full MIT notice in its README.
        if (notices.length === 0 && name === "murmurhash-js") {
          const readme = readFileSync(new URL("README.md", directory), "utf8");
          const start = readme.indexOf("## License (MIT)");
          if (start >= 0) notices.push(readme.slice(start).trimEnd());
        }
        if (!notices.length || notices.some((notice) => !notice.trim())) {
          throw new Error(`No readable license notice for ${name} ${details.version}`);
        }
        sections.push(`## ${name} ${details.version}`, ...notices);
      }
      this.emitFile({
        type: "asset", fileName: "THIRD-PARTY-LICENSES.md", source: sections.join("\n\n") + "\n",
      });
      this.emitFile({
        type: "asset", fileName: "LICENSE", source: readFileSync(new URL("../../../LICENSE", root)),
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), distributionLicenses()],
  server: { host: "localhost", port: 5173, strictPort: true, proxy },
  preview: { host: "localhost", port: 4173, strictPort: true, proxy },
});
