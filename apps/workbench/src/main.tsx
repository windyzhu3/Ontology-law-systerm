import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { SessionApplication } from "./features/session/SessionApplication";
import { createSessionRuntime } from "./features/session/sessionConfiguration";

const runtime = createSessionRuntime(import.meta.env);

const rootElement = document.getElementById("root");
if (rootElement === null) {
  throw new Error("Workbench root element is missing");
}

createRoot(rootElement).render(
  <StrictMode>
    <SessionApplication {...runtime} />
  </StrictMode>,
);
