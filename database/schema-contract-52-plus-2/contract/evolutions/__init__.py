"""Append-only schema contract evolutions after the frozen v1 baseline."""

from .v850_lead_ingress_completion import EVOLUTION as V850_LEAD_INGRESS_COMPLETION
from .v860_lead_ingress_query_read_capability import EVOLUTION as V860_LEAD_INGRESS_QUERY_READ_CAPABILITY


EVOLUTIONS = (V850_LEAD_INGRESS_COMPLETION, V860_LEAD_INGRESS_QUERY_READ_CAPABILITY)

__all__ = ("EVOLUTIONS", "V850_LEAD_INGRESS_COMPLETION", "V860_LEAD_INGRESS_QUERY_READ_CAPABILITY")
