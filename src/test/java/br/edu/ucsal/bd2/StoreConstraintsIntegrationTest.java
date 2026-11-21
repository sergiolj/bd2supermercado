package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.util.MeasurementType;
import br.edu.ucsal.bd2.util.SaleStatus;
import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.EntityManager;

// Regras de loja que o próprio banco garante (db/store_constraints.sql), testadas com SQL direto, sem passar pelos
// services: mesmo um INSERT/UPDATE feito por fora da API é recusado. Roda no PostgreSQL do .env; o @Transactional
// desfaz tudo no fim. Cada recusa roda num SAVEPOINT, porque um erro no PostgreSQL invalida o resto da transação.
@SpringBootTest
@Transactional
class StoreConstraintsIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = "ZZRULE" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private Store pituba;
    private Store barra;
    private Staff pitubaCashier;
    private Staff barraCashier;

    @BeforeEach
    void setUp() {
        pituba = store("Pituba");
        barra = store("Barra");
        pitubaCashier = staff("Caixa Pituba", StaffRole.CASHIER, pituba);
        barraCashier = staff("Caixa Barra", StaffRole.CASHIER, barra);
        em.flush();
    }

    private Store store(String name) {
        Store store = new Store();
        store.setName(tag + " " + name);
        em.persist(store);
        return store;
    }

    private Staff staff(String name, StaffRole role, Store store) {
        Staff staff = new Staff();
        staff.setName(tag + " " + name);
        staff.setTaxNumber(cpf());
        staff.setRole(role);
        staff.setStore(store);
        em.persist(staff);
        return staff;
    }

    private static String cpf() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(10_000_000_000L, 100_000_000_000L));
    }

    // O comando precisa ser recusado pelo banco, citando a regra (nome da constraint ou trecho da mensagem do trigger).
    private void assertRejected(String rule, String sql, Object... args) {
        jdbc.execute("SAVEPOINT store_rule");
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, args));
        jdbc.execute("ROLLBACK TO SAVEPOINT store_rule");
        assertTrue(error.getMostSpecificCause().getMessage().contains(rule), error.getMostSpecificCause().getMessage());
    }

    private Long person(String name) {
        return jdbc.queryForObject("INSERT INTO person (uuid, tax_number, name, created_at, updated_at) "
                + "VALUES (gen_random_uuid(), ?, ?, now(), now()) RETURNING id", Long.class, cpf(), tag + " " + name);
    }

    @Test
    @DisplayName("CHECK: funcionário sem loja só se for ADMIN, e o ADMIN não pode ter loja")
    void shouldTieStoreToRole() {
        assertRejected("ck_staff_store_by_role", "INSERT INTO staff (id, role, store_id) VALUES (?, 'CASHIER', NULL)", person("Sem loja"));
        assertRejected("ck_staff_store_by_role", "INSERT INTO staff (id, role, store_id) VALUES (?, 'ADMIN', ?)", person("Admin"), pituba.getId());

        jdbc.update("INSERT INTO staff (id, role, store_id) VALUES (?, 'ADMIN', NULL)", person("Admin da rede"));
        assertRejected("ck_staff_store_by_role", "UPDATE staff SET role = 'ADMIN' WHERE id = ?", pitubaCashier.getId());
    }

    @Test
    @DisplayName("FK composta: o gerente é funcionário da loja, e não pode ser transferido enquanto a gerencia")
    void shouldKeepManagerInHisStore() {
        assertRejected("fk_store_manager_same_store", "UPDATE store SET manager_id = ? WHERE id = ?", barraCashier.getId(), pituba.getId());

        jdbc.update("UPDATE store SET manager_id = ? WHERE id = ?", pitubaCashier.getId(), pituba.getId());
        assertRejected("fk_store_manager_same_store", "UPDATE staff SET store_id = ? WHERE id = ?", barra.getId(), pitubaCashier.getId());

        // Sem ser gerente, a transferência passa.
        jdbc.update("UPDATE store SET manager_id = NULL WHERE id = ?", pituba.getId());
        assertEquals(1, jdbc.update("UPDATE staff SET store_id = ? WHERE id = ?", barra.getId(), pitubaCashier.getId()));
    }

    @Test
    @DisplayName("Trigger: o vendedor trabalha na loja da venda; transferido depois, a venda antiga continua editável")
    void shouldRequireSellerFromTheSaleStore() {
        assertRejected("nao trabalha na loja", "INSERT INTO sale (uuid, store_id, seller_id, status, total_amount_in_cents, "
                + "created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, 'OPEN', 0, now(), now())", pituba.getId(), barraCashier.getId());

        Long saleId = jdbc.queryForObject("INSERT INTO sale (uuid, store_id, seller_id, status, total_amount_in_cents, created_at, updated_at) "
                + "VALUES (gen_random_uuid(), ?, ?, 'OPEN', 0, now(), now()) RETURNING id", Long.class, pituba.getId(), pitubaCashier.getId());

        jdbc.update("UPDATE staff SET store_id = ? WHERE id = ?", barra.getId(), pitubaCashier.getId());

        // O Hibernate regrava seller_id e store_id junto com o status: com os mesmos valores, o trigger deixa passar.
        assertEquals(1, jdbc.update("UPDATE sale SET status = 'COMPLETED', seller_id = seller_id, store_id = store_id WHERE id = ?", saleId));
        // Trocar o vendedor para alguém de outra loja continua recusado.
        assertRejected("nao trabalha na loja", "UPDATE sale SET seller_id = ? WHERE id = ?", barraCashier.getId(), saleId);
    }

    @Test
    @DisplayName("FK composta: a movimentação de uma venda mexe no estoque da loja da venda, não de outra")
    void shouldMoveStockOfTheSaleStore() {
        Category category = new Category();
        category.setName(tag + " Categoria");
        em.persist(category);
        Product rice = new Product(tag + " Arroz", category, "R" + tag, null, MeasurementType.UNIT);
        em.persist(rice);

        Sale sale = new Sale();
        sale.setStore(pituba);
        sale.setSeller(pitubaCashier);
        sale.setStatus(SaleStatus.COMPLETED);
        sale.setTotalAmountInCents(1000L);
        em.persist(sale);
        em.flush();

        // As duas lojas têm saldo: a única razão para recusar é a loja errada.
        for (Store store : new Store[] {pituba, barra}) {
            jdbc.update("INSERT INTO inventory_movement (store_id, product_id, type, quantity, created_at) VALUES (?, ?, 'PURCHASE', 10, now())",
                    store.getId(), rice.getId());
        }

        assertRejected("fk_inventory_movement_sale_same_store", "INSERT INTO inventory_movement (store_id, product_id, sale_id, type, "
                + "quantity, created_at) VALUES (?, ?, ?, 'SALE', 1, now())", barra.getId(), rice.getId(), sale.getId());

        jdbc.update("INSERT INTO inventory_movement (store_id, product_id, sale_id, type, quantity, created_at) VALUES (?, ?, ?, 'SALE', 1, now())",
                pituba.getId(), rice.getId(), sale.getId());
        assertEquals(9, jdbc.queryForObject("SELECT total_quantity FROM inventory WHERE store_id = ? AND product_id = ?",
                Integer.class, pituba.getId(), rice.getId()));
        assertEquals(10, jdbc.queryForObject("SELECT total_quantity FROM inventory WHERE store_id = ? AND product_id = ?",
                Integer.class, barra.getId(), rice.getId()));
    }
}
