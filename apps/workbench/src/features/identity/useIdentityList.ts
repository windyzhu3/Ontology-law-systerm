import { useCallback, useEffect, useRef, useState } from "react";
import type { WorkbenchSession } from "../../lib/api";

type Page<T> = { items: T[]; nextCursor: string | null };
type PageResult<T> = { data: Page<T> };
type Cursor = string | undefined;

export function useIdentityList<T extends { id: string }>(
  session: WorkbenchSession | null,
  load: (
    query: { limit: number; cursor?: string },
    signal: AbortSignal,
  ) => Promise<PageResult<T>>,
) {
  const identityKey = session
    ? `${session.identityEpoch}:${session.actorScopeKey}:${session.selectedAppointmentId}`
    : "";
  const [navigation, setNavigation] = useState<{
    identityKey: string;
    cursors: Cursor[];
    index: number;
    refresh: number;
  }>({ identityKey: "", cursors: [undefined], index: 0, refresh: 0 });
  const [items, setItems] = useState<T[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestSequence = useRef(0);
  const selectionRef = useRef<string | null>(null);
  const autoSelectRef = useRef(true);
  const reloadWaiters = useRef<Array<(ok: boolean) => void>>([]);
  selectionRef.current = selectedId;

  useEffect(() => {
    if (navigation.identityKey === identityKey) return;
    requestSequence.current += 1;
    setNavigation({ identityKey, cursors: [undefined], index: 0, refresh: 0 });
    setItems(null);
    setNextCursor(null);
    setSelectedId(null);
    setError(null);
    setLoading(false);
    autoSelectRef.current = true;
  }, [identityKey, navigation.identityKey]);

  const cursor = navigation.cursors[navigation.index];
  useEffect(() => {
    if (!session || navigation.identityKey !== identityKey) return;
    const controller = new AbortController();
    const waiters = reloadWaiters.current.splice(0);
    const request = ++requestSequence.current;
    setLoading(true);
    setError(null);
    void load(
      { limit: 20, ...(cursor ? { cursor } : {}) },
      controller.signal,
    ).then(
      ({ data }) => {
        if (
          controller.signal.aborted ||
          request !== requestSequence.current ||
          !session.isCurrent()
        )
          return;
        setItems(data.items);
        setNextCursor(data.nextCursor);
        const selected = selectionRef.current;
        if (autoSelectRef.current) {
          setSelectedId(data.items[0]?.id ?? null);
          autoSelectRef.current = false;
        } else if (selected && !data.items.some((item) => item.id === selected)) {
          setSelectedId(null);
        }
        setLoading(false);
        waiters.forEach((resolve) => resolve(true));
      },
      () => {
        if (controller.signal.aborted || request !== requestSequence.current) return;
        setItems(null);
        setNextCursor(null);
        setSelectedId(null);
        setError("身份管理数据暂时不可用，请重读后再试。");
        setLoading(false);
        waiters.forEach((resolve) => resolve(false));
      },
    );
    return () => { controller.abort(); waiters.forEach((resolve) => resolve(false)); };
  }, [cursor, identityKey, load, navigation.identityKey, navigation.refresh, session]);

  const next = useCallback(() => {
    if (!nextCursor) return;
    autoSelectRef.current = true;
    setItems(null);
    setSelectedId(null);
    setNavigation((current) => ({
      ...current,
      cursors: [...current.cursors.slice(0, current.index + 1), nextCursor],
      index: current.index + 1,
    }));
  }, [nextCursor]);
  const previous = useCallback(() => {
    if (navigation.index === 0) return;
    autoSelectRef.current = true;
    setItems(null);
    setSelectedId(null);
    setNavigation((current) => ({ ...current, index: current.index - 1 }));
  }, [navigation.index]);
  const reload = useCallback(() => {
    autoSelectRef.current = false;
    return new Promise<boolean>((resolve) => {
      reloadWaiters.current.push(resolve);
      setNavigation((current) => ({ ...current, refresh: current.refresh + 1 }));
    });
  }, []);

  return {
    items,
    nextCursor,
    selectedId,
    setSelectedId,
    loading,
    error,
    canPrevious: navigation.index > 0,
    canNext: !!nextCursor,
    next,
    previous,
    reload,
  };
}
