package com.urbanmove.relocation.application;

import com.urbanmove.relocation.application.port.EventDispatcher;
import com.urbanmove.relocation.application.port.IdempotencyRecord;
import com.urbanmove.relocation.application.port.IdempotencyStore;
import com.urbanmove.relocation.application.port.OutgoingEvent;
import com.urbanmove.relocation.application.port.RelocationOrderReader;
import com.urbanmove.relocation.application.port.RelocationOrderWriter;
import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.Identifiers.ZoneId;
import com.urbanmove.relocation.domain.InvalidRelocationException;
import com.urbanmove.relocation.domain.RelocationOrder;
import com.urbanmove.relocation.domain.Route;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caso de uso: crear una orden de recolocación a partir de un déficit de demanda detectado. */
public final class CreateOrderFromHotspotUseCase {
    static final String OPERATION = "HOTSPOT";
    private static final Logger log = LoggerFactory.getLogger(CreateOrderFromHotspotUseCase.class);

    private final RelocationOrderWriter writer;
    private final EventDispatcher dispatcher;
    private final PreviousResults previous;
    private final Supplier<RelocationOrderId> ids;
    private final Supplier<UUID> eventIds;
    private final Clock clock;
    private final Duration deadline;

    public CreateOrderFromHotspotUseCase(RelocationOrderReader reader, RelocationOrderWriter writer,
                                         IdempotencyStore idempotency, EventDispatcher dispatcher,
                                         Supplier<RelocationOrderId> ids, Supplier<UUID> eventIds, Clock clock,
                                         Duration deadline) {
        this.writer = Objects.requireNonNull(writer);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.previous = new PreviousResults(idempotency, reader);
        this.ids = Objects.requireNonNull(ids);
        this.eventIds = Objects.requireNonNull(eventIds);
        this.clock = Objects.requireNonNull(clock);
        this.deadline = Objects.requireNonNull(deadline);
    }

    public HotspotOutcome execute(DemandHotspot hotspot) {
        String key = hotspot.eventId().toString();
        if (previous.find(key, OPERATION).isPresent()) {
            return HotspotOutcome.DUPLICATE;
        }
        Route route;
        try {
            route = new Route(new ZoneId(hotspot.suggestedSourceZoneId()), new ZoneId(hotspot.deficitZoneId()),
                    hotspot.vehicleType(), hotspot.suggestedQuantity());
        } catch (InvalidRelocationException | IllegalArgumentException | NullPointerException e) {
            log.atWarn().addKeyValue("eventId", key).log("Hotspot rejected: " + e.getMessage());
            return HotspotOutcome.REJECTED;
        }
        return create(hotspot, route, key);
    }

    private HotspotOutcome create(DemandHotspot hotspot, Route route, String key) {
        Instant now = clock.instant();
        RelocationOrder order = RelocationOrder.create(ids.get(), route, hotspot.strategy(), hotspot.eventId(),
                now, now.plus(deadline));
        List<OutgoingEvent> events = order.pullDomainEvents().stream()
                .map(event -> new OutgoingEvent(eventIds.get(), hotspot.correlationId(), event))
                .toList();
        try {
            writer.create(order, new IdempotencyRecord(key, OPERATION, order.orderId()), events);
        } catch (DuplicateRequestException e) {
            return HotspotOutcome.DUPLICATE;
        } catch (ActiveRouteOrderExistsException e) {
            log.atInfo().addKeyValue("existingOrderId", String.valueOf(e.existingOrderId()))
                    .log("Hotspot ignored: an active relocation order already covers it");
            return HotspotOutcome.ACTIVE_ORDER_EXISTS;
        }
        log.atInfo().addKeyValue("relocationOrderId", order.orderId().value())
                .addKeyValue("sourceZoneId", route.source().value())
                .addKeyValue("targetZoneId", route.target().value())
                .addKeyValue("quantity", route.quantity())
                .log("Relocation order created");
        dispatcher.dispatch(events);
        return HotspotOutcome.CREATED;
    }
}
