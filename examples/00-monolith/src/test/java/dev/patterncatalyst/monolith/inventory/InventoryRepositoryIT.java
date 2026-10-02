package dev.patterncatalyst.monolith.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tier 3 (integration): {@code @DataJpaTest} against a real, Flyway-migrated
 * Testcontainers Postgres (not an in-memory substitute —
 * {@code Replace.NONE}) — proves {@code InventoryRepository}'s custom finder
 * and the {@code PESSIMISTIC_WRITE} lock query actually work against
 * Postgres, not just compile against Spring Data's method-name parser.
 *
 * <p>Each IT class owns its own Testcontainers Postgres instance (same
 * self-contained pattern as {@code smoke.SixContextsSmokeTest}) rather than
 * sharing one via a common base class — a static container shared across
 * multiple top-level test classes gets stopped after the first class's
 * {@code afterAll} and breaks the second class's connection.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InventoryRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("monolith")
            .withUsername("monolith")
            .withPassword("monolith");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private InventoryRepository repository;

    @Test
    void findBySku_seededWidget_returnsItemWithSeededQuantity() {
        Optional<InventoryItem> found = repository.findBySku("SKU-WIDGET-001");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Standard Widget");
        assertThat(found.get().getQuantityOnHand()).isEqualTo(100);
    }

    @Test
    void findBySku_unknownSku_returnsEmpty() {
        assertThat(repository.findBySku("SKU-DOES-NOT-EXIST")).isEmpty();
    }

    @Test
    void findWithLockBySku_decrementAndSave_persistsThroughTheLockedQuery() {
        InventoryItem item = repository.findWithLockBySku("SKU-GIZMO-003").orElseThrow();
        assertThat(item.getQuantityOnHand()).isEqualTo(5); // seeded value

        item.decrement(2);
        repository.saveAndFlush(item);

        InventoryItem reloaded = repository.findBySku("SKU-GIZMO-003").orElseThrow();
        assertThat(reloaded.getQuantityOnHand()).isEqualTo(3);
    }
}
