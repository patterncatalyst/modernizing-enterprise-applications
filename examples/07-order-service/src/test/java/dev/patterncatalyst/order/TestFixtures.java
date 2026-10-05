package dev.patterncatalyst.order;

/** Test-only constructor access for {@link Customer} (package-private id-assigning constructor). */
final class TestFixtures {

    private TestFixtures() {
    }

    static Customer customer(Long id, String name, String email) {
        return new Customer(id, name, email);
    }
}
