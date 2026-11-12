export class ApiError extends Error {
  constructor(
    message,
    { status = 0, code = "API_ERROR", payload, cause } = {},
  ) {
    super(message, { cause });
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.payload = payload;
  }
}

async function request(path, { query, body, signal } = {}) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query || {})) {
    if (value !== undefined && value !== null) params.set(key, String(value));
  }
  let response;
  try {
    response = await fetch(`/api${path}${params.size ? `?${params}` : ""}`, {
      method: body === undefined ? "GET" : "POST",
      headers: {
        Accept: "application/json",
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (cause) {
    if (signal?.aborted || cause.name === "AbortError") throw cause;
    throw new ApiError(
      "Không kết nối được API. Kiểm tra backend và proxy Vite.",
      {
        code: "NETWORK_ERROR",
        cause,
      },
    );
  }
  let payload;
  try {
    payload = await response.json();
  } catch (cause) {
    if (signal?.aborted || cause.name === "AbortError") throw cause;
    throw new ApiError("Backend không trả JSON hợp lệ. Kiểm tra server Java.", {
      status: response.status,
      code: "INVALID_RESPONSE",
      cause,
    });
  }
  if (!response.ok) {
    throw new ApiError(
      payload?.error?.message || payload?.message || `HTTP ${response.status}`,
      {
        status: response.status,
        code: payload?.error?.code || payload?.status || "HTTP_ERROR",
        payload,
      },
    );
  }
  return payload;
}

export const api = {
  health: (options) => request("/health", options),
  map: (options) => request("/map", options),
  searchPlaces: (
    q,
    { mode = "driving", limit = 10, offset = 0, signal } = {},
  ) => request("/places", { query: { q, mode, limit, offset }, signal }),
  nearest: (
    { lat, lng, mode = "driving", maxDistanceMeters = 2000 },
    options,
  ) =>
    request("/nearest", {
      ...options,
      query: { lat, lng, mode, maxDistanceMeters },
    }),
  route: (body, options) => request("/routes", { ...options, body }),
  algorithms: (options) => request("/algorithms", options),
  runAlgorithm: (body, options) =>
    request("/algorithms/run", { ...options, body }),
  analysisGraphs: (options) => request("/analysis/graphs", options),
  analysisGraph: (graph = "sample", options) =>
    request("/analysis/graph", { ...options, query: { graph } }),
  editAnalysisGraph: (body, options) =>
    request("/analysis/graph/edit", { ...options, body }),
  traffic: (options) => request("/traffic", options),
  updateTraffic: (body, options) =>
    request("/traffic/update", { ...options, body }),
};
