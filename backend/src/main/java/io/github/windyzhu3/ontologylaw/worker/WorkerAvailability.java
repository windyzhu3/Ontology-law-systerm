package io.github.windyzhu3.ontologylaw.worker;

import org.springframework.boot.availability.*;

/** Boot's final startup event cannot declare an unready required loop healthy. */
final class WorkerAvailability extends ApplicationAvailabilityBean {
    private final WorkerRuntimeHealth health;
    WorkerAvailability(WorkerRuntimeHealth health){this.health=health;}
    @Override public void onApplicationEvent(AvailabilityChangeEvent<?> event) {
        if(event.getState()==ReadinessState.ACCEPTING_TRAFFIC&&!health.healthy())
            super.onApplicationEvent(new AvailabilityChangeEvent<>(event.getSource(),ReadinessState.REFUSING_TRAFFIC));
        else super.onApplicationEvent(event);
    }
}
