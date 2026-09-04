package io.github.windyzhu3.ontologylaw.bootstrap;

public enum RuntimeRole {
    API,
    WORKER;

    static RuntimeRole parse(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("ols.runtime-role is required");
        }
        return switch (value) {
            case "api" -> API;
            case "worker" -> WORKER;
            default -> throw new IllegalStateException(
                    "ols.runtime-role must be exactly 'api' or 'worker'");
        };
    }
}
