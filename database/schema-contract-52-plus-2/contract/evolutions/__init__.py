"""Append-only schema contract evolutions after the frozen v1 baseline."""

from .v850_lead_ingress_completion import EVOLUTION as V850_LEAD_INGRESS_COMPLETION


EVOLUTIONS = (V850_LEAD_INGRESS_COMPLETION,)

__all__ = ("EVOLUTIONS", "V850_LEAD_INGRESS_COMPLETION")
