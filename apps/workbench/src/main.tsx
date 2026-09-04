import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

function WorkbenchShell() {
  return <main aria-label="Ontology Law Workbench" />;
}

const rootElement = document.getElementById("root");
if (rootElement === null) {
  throw new Error("Workbench root element is missing");
}

createRoot(rootElement).render(
  <StrictMode>
    <WorkbenchShell />
  </StrictMode>,
);
