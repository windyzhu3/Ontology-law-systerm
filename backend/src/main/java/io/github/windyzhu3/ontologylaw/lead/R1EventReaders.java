package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.execution.R1EventFacts;
import io.github.windyzhu3.ontologylaw.opportunity.EventOpportunityReader;
import io.github.windyzhu3.ontologylaw.responsibility.EventResponsibilityReader;

/** Downstream assembly preserves the frozen execution/Owner dependency DAG. */
public final class R1EventReaders {
    private R1EventReaders() {}
    public static R1EventFacts databaseBacked(){return new io.github.windyzhu3.ontologylaw.lead.internal.persistence.JooqR1EventFacts(EventResponsibilityReader.databaseBacked(),EventOpportunityReader.databaseBacked());}
}
