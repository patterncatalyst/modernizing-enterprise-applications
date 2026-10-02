package dev.patterncatalyst.monolith.order;

import static org.assertj.core.api.Assertions.assertThat;

import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.inventory.InventoryItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tier 3 (integration): {@code @DataJpaTest} against a real Testcontainers
 * Postgres — proves two things static analysis can't: (1) the SMELL[ch.18]
 * cross-context FK joins (order -&gt; customer, order_item -&gt;
 * inventory_item) actually resolve through Hibernate's {@code @ManyToOne}
 * mappings against the Flyway-migrated schema, and (2) {@code Order}'s
 * {@code cascade = ALL} on {@code items} really persists {@code OrderItem}
 * rows transitively, not just in memory.
 *
 * <p>Owns its own Testcontainers Postgres instance (see the note on
 * {@code InventoryRepositoryIT} for why it isn't shared via a base class).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryIT {

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
    private OrderRepository orderRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findById_seedOrder_resolvesCrossContextJoinsToCustomerAndInventoryItem() {
        Order order = orderRepository.findById(1L).orElseThrow();

        // SMELL[ch.18]: direct FK join into the shared customers table.
        assertThat(order.getCustomer().getName()).isEqualTo("Ada Lovelace");
        assertThat(order.getItems()).hasSize(1);
        // SMELL[ch.18]: direct FK join into the shared inventory_items table.
        assertThat(order.getItems().get(0).getInventoryItem().getSku()).isEqualTo("SKU-WIDGET-001");
        assertThat(order.getTotalCents()).isEqualTo(3998L);
    }

    @Test
    void save_newOrderWithItems_cascadesOrderItemsOnFlushAndReload() {
        Customer customer = entityManager.find(Customer.class, 2L); // Grace Hopper
        InventoryItem gadget = entityManager.find(InventoryItem.class, 2L); // SKU-GADGET-002

        Order order = new Order(customer, "99 Test Street");
        order.addItem(new OrderItem(gadget, 2, gadget.getPriceCents()));

        Order saved = orderRepository.saveAndFlush(order);
        entityManager.clear(); // force a real reload, proving the OrderItem rows were persisted

        Order reloaded = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getItems().get(0).getInventoryItem().getSku()).isEqualTo("SKU-GADGET-002");
        assertThat(reloaded.getTotalCents()).isEqualTo(gadget.getPriceCents() * 2);
    }
}
