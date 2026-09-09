import type { WorkbenchSession } from "../../lib/sessionTransport";
import type { IdentityApi } from "./identityApi";
import type { IdentityCommand } from "./useIdentityCommand";
import { IdentityCreateForm } from "./IdentityCreateForm";
import { IdentityRenameForm } from "./IdentityRenameForm";

export function identityDetailEditor(command: IdentityCommand, session: WorkbenchSession, api: IdentityApi) {
  const editor = command.editor;
  if (editor?.kind === "create") return <IdentityCreateForm key={editor.page} page={editor.page} command={command} session={session} api={api} />;
  if (editor?.kind === "rename") return <IdentityRenameForm key={editor.targetId} editor={editor} command={command} />;
  return null;
}
