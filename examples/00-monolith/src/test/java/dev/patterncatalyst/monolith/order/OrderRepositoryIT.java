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
 * cross-context FK join (order -&gt; customer) actually resolves through
 * Hibernate's {@code @ManyToOne} mapping against the Flyway-migrated schema
 * (the order_item -&gt; inventory_item FK was decomposed in r05/ch.19 S8,
 * DRQ-043 — {@code OrderItem} now persists a denormalized sku/name/price
 * snapshot instead, a plain soft reference with no DB FK/JPA association),
 * and (2) {@code Order}'s {@code cascade = ALL} on {@code items} really
 * persists {@code OrderItem} rows transitively, not just in memory.
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
    void findById_seedOrder_resolvesCustomerJoinAndOrderItemSnapshot() {
        Order order = orderRepository.findById(1L).orElseThrow();

        // SMELL[ch.18]: direct FK join into the shared customers table.
        assertThat(order.getCustomer().getName()).isEqualTo("Ada Lovelace");
        assertThat(order.getItems()).hasSize(1);
        // r05/ch.19 S8 (DRQ-043): no FK/join -- a denormalized snapshot on OrderItem itself.
        assertThat(order.getItems().get(0).getSku()).isEqualTo("SKU-WIDGET-001");
        assertThat(order.getTotalCents()).isEqualTo(3998L);
    }

    @Test
    void save_newOrderWithItems_cascadesOrderItemsOnFlushAndReload() {
        Customer customer = entityManager.find(Customer.class, 2L); // Grace Hopper
        InventoryItem gadget = entityManager.find(InventoryItem.class, 2L); // SKU-GADGET-002

        Order order = new Order(customer, "99 Test Street");
        order.addItem(new OrderItem(gadget.getSku(), gadget.getName(), 2, gadget.getPriceCents()));

        Order saved = orderRepository.saveAndFlush(order);
        entityManager.clear(); // force a real reload, proving the OrderItem rows were persisted

        Order reloaded = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getItems().get(0).getSku()).isEqualTo("SKU-GADGET-002");
        assertThat(reloaded.getItems().get(0).getProductName()).isEqualTo(gadget.getName());
        assertThat(reloaded.getTotalCents()).isEqualTo(gadget.getPriceCents() * 2);
    }
}
