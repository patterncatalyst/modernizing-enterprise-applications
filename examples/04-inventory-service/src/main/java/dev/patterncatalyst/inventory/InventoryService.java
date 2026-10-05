package dev.patterncatalyst.inventory;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * REFACTORED to idiomatic Quarkus (r05/ch.19 S6 Phase B, DRQ-029/DRQ-044)
 * from the Phase A lift. The Spring {@code @Service} stereotype is dropped
 * -- plain CDI {@code @ApplicationScoped} plus Quarkus's simplified
 * constructor injection (a single constructor needs no {@code @Inject}) is
 * the whole DI story now, mirroring review-service's/notification-service's
 * Phase B.
 *
 * <p>{@code listAll}/{@code getBySku}/{@code findBySkuOrThrow}/{@code toDto}
 * are byte-for-byte the same business logic as Phase A; only
 * {@code repository.findAll()} becomes {@code repository.listAll()} (Panache
 * repository's equivalent). The mutating {@code reserve}/{@code release}
 * concern does NOT live here -- it is the gRPC {@code Reserve}/{@code
 * Release} RPCs' server-side implementation ({@link InventoryGrpcServiceImpl}
 * delegating to {@link InventoryRepository#reserve}/{@link
 * InventoryRepository#release}), idiomatic-from-start per DRQ-041/DRQ-044,
 * not a REST-surface concern.
 */
@ApplicationScoped
public class InventoryService {

    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    public List<StockDto> listAll() {
        return repository.listAll().stream().map(InventoryService::toDto).toList();
    }

    public StockDto getBySku(String sku) {
        return toDto(findBySkuOrThrow(sku));
    }

    public InventoryItem findBySkuOrThrow(String sku) {
        return repository.findBySku(sku)
                .orElseThrow(() -> new ResourceNotFoundException("No inventory item with sku " + sku));
    }

    private static StockDto toDto(InventoryItem item) {
        return new StockDto(item.getSku(), item.getName(), item.getPriceCents(), item.getQuantityOnHand());
    }
}
