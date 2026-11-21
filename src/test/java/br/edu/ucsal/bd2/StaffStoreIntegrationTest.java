package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dto.request.CustomerRequest;
import br.edu.ucsal.bd2.dto.request.StaffRequest;
import br.edu.ucsal.bd2.dto.request.StoreRequest;
import br.edu.ucsal.bd2.dto.response.StoreResponse;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.service.CustomerService;
import br.edu.ucsal.bd2.service.StaffService;
import br.edu.ucsal.bd2.service.StoreService;
import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityNotFoundException;

// Roda as consultas JPQL dos DAOs de funcionário e loja de verdade no PostgreSQL do .env.
// O @Transactional desfaz tudo no fim, então funciona com o banco vazio ou com o seed. Os nomes começam com um
// prefixo único para sair primeiro nas listagens ordenadas por nome, mesmo com o seed no banco.
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class StaffStoreIntegrationTest {

    @Autowired
    private StaffService staffService;

    @Autowired
    private StoreService storeService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory emf;

    private final String tag = "AAJPQL" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private static String cpf() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(10_000_000_000L, 100_000_000_000L));
    }

    private Staff staff(String name, StaffRole role) {
        return staffService.createStaff(new StaffRequest(cpf(), tag + " " + name, role, null, null, null, null, null, null, null, null));
    }

    private Store store(String name, Staff manager) {
        return storeService.createStore(new StoreRequest(tag + " " + name, manager == null ? null : manager.getUuid(),
                "Av. Manoel Dias", "1200", "Pituba", "Salvador", "BA", "41830000"));
    }

    @Test
    @DisplayName("O CPF é único entre funcionários e clientes (a tabela person é uma só)")
    void shouldShareTaxNumberUniquenessWithCustomers() {
        String cpf = cpf();
        staffService.createStaff(new StaffRequest(cpf, tag + " Funcionario", StaffRole.CASHIER, null, null, null, null, null, null, null, null));

        assertThrows(EntityExistsException.class, () -> customerService.createCustomer(
                new CustomerRequest(cpf, tag + " Cliente", null, null, null, null, null, null, null, null)));

        String customerCpf = cpf();
        customerService.createCustomer(new CustomerRequest(customerCpf, tag + " Cliente 2", null, null, null, null, null, null, null, null));
        assertThrows(EntityExistsException.class, () -> staffService.createStaff(
                new StaffRequest(customerCpf, tag + " Funcionario 2", StaffRole.CASHIER, null, null, null, null, null, null, null, null)));
    }

    @Test
    @DisplayName("Atualizar mantendo o próprio CPF é permitido, e usar o de outro funcionário é recusado")
    void shouldAllowKeepingOwnTaxNumberOnUpdate() {
        Staff a = staff("A", StaffRole.CASHIER);
        Staff b = staff("B", StaffRole.CASHIER);

        Staff updated = staffService.updateStaff(a.getUuid(),
                new StaffRequest(a.getTaxNumber(), tag + " A renomeado", StaffRole.MANAGER, null, null, null, null, null, null, null, null));
        assertEquals(tag + " A renomeado", updated.getName());
        assertEquals(StaffRole.MANAGER, updated.getRole());

        assertThrows(EntityExistsException.class, () -> staffService.updateStaff(a.getUuid(),
                new StaffRequest(b.getTaxNumber(), tag + " A", StaffRole.MANAGER, null, null, null, null, null, null, null, null)));
    }

    @Test
    @DisplayName("A listagem pagina com ordem estável (nomes iguais não repetem nem somem entre páginas) e esconde os apagados")
    void shouldPaginateStaffWithStableOrder() {
        Staff first = staff("Mesmo Nome", StaffRole.CASHIER);
        Staff second = staff("Mesmo Nome", StaffRole.CASHIER);
        Staff third = staff("Mesmo Nome", StaffRole.CASHIER);
        Staff gone = staff("Apagado", StaffRole.CASHIER);
        staffService.deleteStaff(gone.getUuid());
        em.flush();

        Set<UUID> seen = new HashSet<>();
        for (int page = 0; page < 2; page++) {
            for (Staff staff : staffService.findAll(PageRequest.of(page, 2)).getContent()) {
                if (staff.getName().startsWith(tag)) {
                    assertTrue(seen.add(staff.getUuid()), "funcionário repetido entre páginas: " + staff.getName());
                }
            }
        }
        assertEquals(Set.of(first.getUuid(), second.getUuid(), third.getUuid()), seen);
        assertThrows(EntityNotFoundException.class, () -> staffService.findByUuid(gone.getUuid()));
    }

    @Test
    @DisplayName("Um funcionário gerencia no máximo uma loja, e não dá para trocar a loja para o gerente de outra")
    void shouldAllowOneStorePerManager() {
        Staff manager = staff("Gerente", StaffRole.MANAGER);
        Store first = store("Loja 1", manager);
        assertEquals(manager.getUuid(), first.getManager().getUuid());

        assertThrows(EntityExistsException.class, () -> store("Loja 2", manager));

        Store second = store("Loja 2", null);
        assertThrows(EntityExistsException.class, () -> storeService.updateStore(second.getUuid(),
                new StoreRequest(tag + " Loja 2", manager.getUuid(), null, null, null, null, null, null)));

        // A própria loja pode reafirmar o seu gerente.
        Store same = storeService.updateStore(first.getUuid(),
                new StoreRequest(tag + " Loja 1", manager.getUuid(), null, null, null, null, null, null));
        assertEquals(manager.getUuid(), same.getManager().getUuid());
    }

    @Test
    @DisplayName("O nome da loja é único, inclusive o de uma loja apagada")
    void shouldKeepStoreNameUnique() {
        Store first = store("Loja Unica", null);

        assertThrows(EntityExistsException.class, () -> store("Loja Unica", null));

        storeService.deleteStore(first.getUuid());
        em.flush();
        assertThrows(EntityExistsException.class, () -> store("Loja Unica", null)); // a coluna é única no banco inteiro
    }

    @Test
    @DisplayName("Apagar a loja solta o gerente: ele pode ser apagado e a loja some da listagem")
    void shouldReleaseManagerWhenStoreIsDeleted() {
        Staff manager = staff("Gerente", StaffRole.MANAGER);
        Store store = store("Loja", manager);

        // Enquanto gerencia a loja, o funcionário não pode ser apagado.
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> staffService.deleteStaff(manager.getUuid()));
        assertEquals(409, error.getStatusCode().value());

        storeService.deleteStore(store.getUuid());
        em.flush();
        assertNull(store.getManager());
        assertThrows(EntityNotFoundException.class, () -> storeService.findByUuid(store.getUuid()));

        staffService.deleteStaff(manager.getUuid()); // agora pode
        em.flush();
        assertThrows(EntityNotFoundException.class, () -> staffService.findByUuid(manager.getUuid()));
    }

    @Test
    @DisplayName("Um gerente apagado não pode ser indicado como gerente de loja (404)")
    void shouldRejectDeletedManager() {
        Staff gone = staff("Ex-funcionario", StaffRole.MANAGER);
        staffService.deleteStaff(gone.getUuid());
        em.flush();

        assertThrows(EntityNotFoundException.class, () -> store("Loja", gone));
    }

    @Test
    @DisplayName("Listar lojas traz o gerente no mesmo SELECT (JOIN FETCH): 2 consultas para a página, não uma por loja")
    void shouldListStoresWithManagersWithoutNPlusOne() {
        for (int i = 1; i <= 4; i++) {
            store("Loja " + i, staff("Gerente " + i, StaffRole.MANAGER));
        }
        store("Loja sem gerente", null);
        em.flush();
        em.clear(); // sem nada em memória: se o gerente não vier no SELECT, cada loja dispara uma consulta extra

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Page<Store> page = storeService.findAll(PageRequest.of(0, 100));
        List<StoreResponse> mine = page.getContent().stream()
                .map(StoreResponse::convertToDTO)
                .filter(r -> r.name().startsWith(tag))
                .toList();

        assertEquals(5, mine.size());
        assertEquals(tag + " Gerente 1", mine.get(0).managerName());
        assertNull(mine.get(4).managerUuid()); // a loja sem gerente sai com gerente nulo (é um LEFT JOIN)
        assertEquals(2, statistics.getPrepareStatementCount(), "página + total; qualquer coisa a mais é N+1");
    }

    @Test
    @DisplayName("Buscar uma loja por uuid também traz o gerente de uma vez")
    void shouldFindStoreWithManagerInOneQuery() {
        Store created = store("Loja", staff("Gerente", StaffRole.MANAGER));
        em.flush();
        em.clear();

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Store found = storeService.findByUuid(created.getUuid());
        assertEquals(tag + " Gerente", StoreResponse.convertToDTO(found).managerName());
        assertEquals(1, statistics.getPrepareStatementCount());
    }
}
