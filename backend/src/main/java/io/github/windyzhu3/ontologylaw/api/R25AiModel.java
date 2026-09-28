package io.github.windyzhu3.ontologylaw.api;
import java.util.List;
@FunctionalInterface
public interface R25AiModel {
    R25AiCandidateContract.Result generate(R25AiCandidateContract.Task task,List<R25AiCandidateContract.Source> sources);
}
