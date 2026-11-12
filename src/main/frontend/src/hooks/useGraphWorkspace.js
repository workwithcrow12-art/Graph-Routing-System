import { useEffect, useRef, useState } from "react";
import { api } from "../api.js";

export function useGraphWorkspace() {
  const [graphs, setGraphs] = useState([]);
  const [algorithms, setAlgorithms] = useState([]);
  const [selectedGraph, setSelectedGraph] = useState("loaded");
  const [graph, setGraph] = useState(null);
  const [form, setForm] = useState({
    algorithm: "DIJKSTRA",
    startId: "",
    goalId: "",
    maxTraceSteps: 10000,
  });
  const [runs, setRuns] = useState([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [editing, setEditing] = useState(false);
  const [revision, setRevision] = useState(0);
  const operation = useRef(null);
  const descriptor = algorithms.find((item) => item.id === form.algorithm);
  const spanningTree = descriptor?.category === "SPANNING_TREE";
  const context = JSON.stringify([
    selectedGraph, graph?.revision, spanningTree ? "tree" : "route",
    spanningTree ? null : form.startId, spanningTree ? null : form.goalId,
  ]);
  const comparisons = runs.filter((run) => run.context === context).map((run) => run.result);
  const result = running ? null : comparisons.find((run) => run.algorithm === form.algorithm) || null;

  function updateEndpoints(data) {
    setForm((previous) => ({
      ...previous,
      startId: data.nodes.some((node) => node.id === previous.startId)
        ? previous.startId
        : data.nodes[0]?.id || "",
      goalId: data.nodes.some((node) => node.id === previous.goalId)
        ? previous.goalId
        : data.nodes.at(-1)?.id || "",
    }));
  }

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      api.analysisGraphs({ signal: controller.signal }),
      api.algorithms({ signal: controller.signal }),
    ])
      .then(([datasets, catalog]) => {
        if (!controller.signal.aborted) {
          setGraphs(datasets);
          setAlgorithms(catalog);
        }
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(failure.message);
      });
    return () => controller.abort();
  }, [revision]);

  useEffect(() => {
    const controller = new AbortController();
    operation.current?.abort();
    setRunning(false);
    setEditing(false);
    setLoading(true);
    setError("");
    setRuns([]);
    setGraph(null);
    api
      .analysisGraph(selectedGraph, { signal: controller.signal })
      .then((data) => {
        if (controller.signal.aborted) return;
        setGraph(data);
        updateEndpoints(data);
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(failure.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => {
      controller.abort();
      operation.current?.abort();
    };
  }, [selectedGraph, revision]);

  async function run() {
    if (!graph || running || editing) return;
    if (!Number.isInteger(form.maxTraceSteps) || form.maxTraceSteps < 0 || form.maxTraceSteps > 10000) {
      setError("Số bước phải là số nguyên từ 0 đến 10.000.");
      return;
    }
    const availability = graphs.find((item) => item.id === selectedGraph)?.algorithms
      .find((item) => item.algorithm === form.algorithm);
    if (availability?.available === false) {
      setError(availability.message);
      return;
    }
    operation.current?.abort();
    const controller = new AbortController();
    operation.current = controller;
    setRunning(true);
    setError("");
    const request = {
      algorithm: form.algorithm,
      graph: selectedGraph,
      maxTraceSteps: form.maxTraceSteps,
    };
    if (descriptor?.category !== "SPANNING_TREE") {
      if (form.startId) request.startId = form.startId;
      if (form.goalId) request.goalId = form.goalId;
    }
    try {
      const response = await api.runAlgorithm(request, {
        signal: controller.signal,
      });
      if (!controller.signal.aborted) setRuns((previous) => {
        const matching = previous.findIndex((run) => run.context === context
          && run.result.algorithm === response.algorithm);
        const record = { context, result: response };
        if (matching < 0) return [...previous, record].slice(-40);
        return previous.map((run, index) => index === matching ? record : run);
      });
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure.message);
    } finally {
      if (!controller.signal.aborted) setRunning(false);
    }
  }

  async function edit(change) {
    if (!graph || selectedGraph !== "custom" || editing || running)
      return false;
    operation.current?.abort();
    const controller = new AbortController();
    operation.current = controller;
    setEditing(true);
    setError("");
    try {
      const updated = await api.editAnalysisGraph(
        { ...change, expectedRevision: graph.revision },
        { signal: controller.signal },
      );
      if (controller.signal.aborted) return false;
      setGraph(updated);
      setRuns([]);
      updateEndpoints(updated);
      try {
        const catalog = await api.analysisGraphs({ signal: controller.signal });
        if (!controller.signal.aborted) setGraphs(catalog);
      } catch (failure) {
        if (!controller.signal.aborted)
          setError(
            `Đã lưu graph nhưng chưa tải lại được danh sách: ${failure.message}`,
          );
      }
      return true;
    } catch (failure) {
      if (!controller.signal.aborted) setError(failure.message);
      return false;
    } finally {
      if (!controller.signal.aborted) setEditing(false);
    }
  }

  return {
    graphs,
    algorithms,
    selectedGraph,
    setSelectedGraph,
    graph,
    form,
    setForm,
    result,
    comparisons,
    error,
    loading,
    running,
    editing,
    run,
    edit,
    reload: () => setRevision((value) => value + 1),
  };
}
