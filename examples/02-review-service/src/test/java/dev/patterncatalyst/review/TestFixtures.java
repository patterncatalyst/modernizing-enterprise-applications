package dev.patterncatalyst.review;

/**
 * Test-only constructors for the minimal, package-private
 * {@link Customer}/{@link InventoryItem} shared-schema projections (see their
 * class javadoc — real id/name/email/sku live in the monolith's full shared-
 * kernel entities; this service only mirrors the fields it actually reads).
 */
final class TestFixtures {

    private TestFixtures() {
    }

    static Customer customer(Long id) {
        return new Customer(id);
    }

    static InventoryItem inventoryItem(Long id, String sku) {
        return new InventoryItem(id, sku);
    }
}
