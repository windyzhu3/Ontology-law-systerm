package io.github.windyzhu3.ontologylaw.api;

import org.springframework.boot.availability.*;

/** Boot's final startup event cannot override the current deployment gate. */
final class ApiAvailability extends ApplicationAvailabilityBean {
    private final ApiRuntimeHealth health;
    ApiAvailability(ApiRuntimeHealth health){this.health=health;}
    @Override public void onApplicationEvent(AvailabilityChangeEvent<?> event) {
        if(event.getState()==ReadinessState.ACCEPTING_TRAFFIC&&!health.healthy())
            super.onApplicationEvent(new AvailabilityChangeEvent<>(event.getSource(),ReadinessState.REFUSING_TRAFFIC));
        else super.onApplicationEvent(event);
    }
}
