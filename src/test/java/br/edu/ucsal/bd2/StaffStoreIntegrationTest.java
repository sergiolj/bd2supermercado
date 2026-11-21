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
import org.junit.jupiter.api.BeforeEach;
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
import br.edu.ucsal.bd2.dto.response.StaffResponse;
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

    // Loja onde os funcionários dos testes trabalham, salvo quando o teste diz outra.
    private Store home;

    @BeforeEach
    void setUp() {
        home = store("Matriz");
    }

    private static String cpf() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(10_000_000_000L, 100_000_000_000L));
    }

    private StaffRequest staffRequest(String cpf, String name, StaffRole role, Store store) {
        return new StaffRequest(cpf, tag + " " + name, role, store == null ? null : store.getUuid(),
                null, null, null, null, null, null, null, null);
    }

    private Staff staff(String name, StaffRole role) {
        return staff(name, role, home);
    }

    private Staff staff(String name, StaffRole role, Store store) {
        return staffService.createStaff(staffRequest(cpf(), name, role, store));
    }

    private Store store(String name) {
        return storeService.createStore(new StoreRequest(tag + " " + name, null,
                "Av. Manoel Dias", "1200", "Pituba", "Salvador", "BA", "41830000"));
    }

    private Store setManager(Store store, Staff manager) {
        return storeService.updateStore(store.getUuid(), new StoreRequest(store.getName(), manager == null ? null : manager.getUuid(),
                null, null, null, null, null, null));
    }

    // Loja com um gerente que trabalha nela: cria a loja, contrata o gerente nela e o define como gerente.
    private Store storeWithManager(String name, String managerName) {
        Store store = store(name);
        return setManager(store, staff(managerName, StaffRole.MANAGER, store));
    }

    @Test
    @DisplayName("O CPF é único entre funcionários e clientes (a tabela person é uma só)")
    void shouldShareTaxNumberUniquenessWithCustomers() {
        String cpf = cpf();
        staffService.createStaff(staffRequest(cpf, "Funcionario", StaffRole.CASHIER, home));

        assertThrows(EntityExistsException.class, () -> customerService.createCustomer(
                new CustomerRequest(cpf, tag + " Cliente", null, null, null, null, null, null, null, null)));

        String customerCpf = cpf();
        customerService.createCustomer(new CustomerRequest(customerCpf, tag + " Cliente 2", null, null, null, null, null, null, null, null));
        assertThrows(EntityExistsException.class, () -> staffService.createStaff(
                staffRequest(customerCpf, "Funcionario 2", StaffRole.CASHIER, home)));
    }

    @Test
    @DisplayName("Atualizar mantendo o próprio CPF é permitido, e usar o de outro funcionário é recusado")
    void shouldAllowKeepingOwnTaxNumberOnUpdate() {
        Staff a = staff("A", StaffRole.CASHIER);
        Staff b = staff("B", StaffRole.CASHIER);

        Staff updated = staffService.updateStaff(a.getUuid(), staffRequest(a.getTaxNumber(), "A renomeado", StaffRole.MANAGER, home));
        assertEquals(tag + " A renomeado", updated.getName());
        assertEquals(StaffRole.MANAGER, updated.getRole());

        assertThrows(EntityExistsException.class, () -> staffService.updateStaff(a.getUuid(),
                staffRequest(b.getTaxNumber(), "A", StaffRole.MANAGER, home)));
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
            for (Staff staff : staffService.findAll(null, PageRequest.of(page, 2)).getContent()) {
                if (staff.getName().startsWith(tag)) {
                    assertTrue(seen.add(staff.getUuid()), "funcionário repetido entre páginas: " + staff.getName());
                }
            }
        }
        assertEquals(Set.of(first.getUuid(), second.getUuid(), third.getUuid()), seen);
        assertThrows(EntityNotFoundException.class, () -> staffService.findByUuid(gone.getUuid()));
    }

    @Test
    @DisplayName("Com loja, a listagem traz só quem trabalha nela, com a loja no mesmo SELECT (sem N+1)")
    void shouldListStaffOfOneStoreWithoutNPlusOne() {
        Store other = store("Filial");
        Staff mine = staff("Caixa da Matriz", StaffRole.CASHIER);
        Staff theirs = staff("Caixa da Filial", StaffRole.CASHIER, other);
        Staff admin = staff("Admin", StaffRole.ADMIN, null);
        em.flush();
        em.clear(); // sem nada em memória: se a loja não vier no SELECT, cada funcionário dispara uma consulta extra

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<StaffResponse> page = staffService.findAll(home.getUuid(), PageRequest.of(0, 100)).getContent().stream()
                .map(StaffResponse::convertToDTO)
                .toList();

        assertEquals(List.of(mine.getUuid()), page.stream().map(StaffResponse::uuid).toList());
        assertEquals(tag + " Matriz", page.get(0).storeName());
        assertEquals(3, statistics.getPrepareStatementCount(), "loja do header + página + total; qualquer coisa a mais é N+1");

        List<UUID> network = staffService.findAll(null, PageRequest.of(0, 100)).getContent().stream().map(Staff::getUuid).toList();
        assertTrue(network.containsAll(List.of(mine.getUuid(), theirs.getUuid(), admin.getUuid())));
    }

    @Test
    @DisplayName("Transferir o funcionário é trocar a loja no PUT; o gerente só sai depois de trocado o gerente da loja")
    void shouldTransferStaffButNotTheManager() {
        Store other = store("Filial");
        Staff cashier = staff("Caixa", StaffRole.CASHIER);

        Staff moved = staffService.updateStaff(cashier.getUuid(), staffRequest(cashier.getTaxNumber(), "Caixa", StaffRole.CASHIER, other));
        assertEquals(other.getUuid(), moved.getStore().getUuid());

        Store managed = storeWithManager("Loja", "Gerente");
        Staff manager = managed.getManager();
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> staffService.updateStaff(manager.getUuid(),
                staffRequest(manager.getTaxNumber(), "Gerente", StaffRole.MANAGER, other)));
        assertEquals(409, error.getStatusCode().value());

        setManager(managed, null);
        Staff released = staffService.updateStaff(manager.getUuid(), staffRequest(manager.getTaxNumber(), "Gerente", StaffRole.MANAGER, other));
        em.flush(); // a FK composta do banco também aceita: ele não gerencia mais nenhuma loja
        assertEquals(other.getUuid(), released.getStore().getUuid());
    }

    @Test
    @DisplayName("O gerente precisa trabalhar na loja: funcionário de outra loja é 409, e cada um gerencia no máximo uma loja")
    void shouldRequireManagerFromTheSameStore() {
        Store first = storeWithManager("Loja 1", "Gerente");
        Staff manager = first.getManager();
        Store second = store("Loja 2");

        assertThrows(ResponseStatusException.class, () -> setManager(second, manager));
        assertThrows(ResponseStatusException.class, () -> setManager(second, staff("Admin", StaffRole.ADMIN, null)));

        // A própria loja pode reafirmar o seu gerente.
        Store same = setManager(first, manager);
        assertEquals(manager.getUuid(), same.getManager().getUuid());
    }

    @Test
    @DisplayName("Loja nova não nasce com gerente (400)")
    void shouldRejectManagerOnCreate() {
        Staff someone = staff("Alguem", StaffRole.MANAGER);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> storeService.createStore(
                new StoreRequest(tag + " Nova", someone.getUuid(), null, null, null, null, null, null)));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    @DisplayName("O nome da loja é único, inclusive o de uma loja apagada")
    void shouldKeepStoreNameUnique() {
        Store first = store("Loja Unica");

        assertThrows(EntityExistsException.class, () -> store("Loja Unica"));

        storeService.deleteStore(first.getUuid());
        em.flush();
        assertThrows(EntityExistsException.class, () -> store("Loja Unica")); // a coluna é única no banco inteiro
    }

    @Test
    @DisplayName("Loja com funcionários ativos não pode ser apagada; sem gerente e sem funcionários, pode")
    void shouldDeleteStoreOnlyWithoutActiveStaff() {
        Store store = storeWithManager("Loja", "Gerente");
        Staff manager = store.getManager();
        Staff cashier = staff("Caixa", StaffRole.CASHIER, store);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> storeService.deleteStore(store.getUuid()));
        assertEquals(409, error.getStatusCode().value());

        // Enquanto gerencia a loja, o funcionário não pode ser apagado.
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> staffService.deleteStaff(manager.getUuid())).getStatusCode().value());

        setManager(store, null);
        staffService.deleteStaff(manager.getUuid());
        staffService.updateStaff(cashier.getUuid(), staffRequest(cashier.getTaxNumber(), "Caixa", StaffRole.CASHIER, home));
        storeService.deleteStore(store.getUuid());
        em.flush();

        assertThrows(EntityNotFoundException.class, () -> storeService.findByUuid(store.getUuid()));
        assertThrows(EntityNotFoundException.class, () -> staffService.createStaff(staffRequest(cpf(), "Novo", StaffRole.CASHIER, store)));
    }

    @Test
    @DisplayName("Um gerente apagado não pode ser indicado como gerente de loja (404)")
    void shouldRejectDeletedManager() {
        Staff gone = staff("Ex-funcionario", StaffRole.MANAGER);
        staffService.deleteStaff(gone.getUuid());
        em.flush();

        assertThrows(EntityNotFoundException.class, () -> setManager(home, gone));
    }

    @Test
    @DisplayName("Listar lojas traz o gerente no mesmo SELECT (JOIN FETCH): 2 consultas para a página, não uma por loja")
    void shouldListStoresWithManagersWithoutNPlusOne() {
        for (int i = 1; i <= 4; i++) {
            storeWithManager("Loja " + i, "Gerente " + i);
        }
        store("Loja sem gerente");
        em.flush();
        em.clear(); // sem nada em memória: se o gerente não vier no SELECT, cada loja dispara uma consulta extra

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Page<Store> page = storeService.findAll(PageRequest.of(0, 100));
        List<StoreResponse> mine = page.getContent().stream()
                .map(StoreResponse::convertToDTO)
                .filter(r -> r.name().startsWith(tag) && r.name().contains("Loja"))
                .toList();

        assertEquals(5, mine.size());
        assertEquals(tag + " Gerente 1", mine.get(0).managerName());
        assertNull(mine.get(4).managerUuid()); // a loja sem gerente sai com gerente nulo (é um LEFT JOIN)
        assertEquals(2, statistics.getPrepareStatementCount(), "página + total; qualquer coisa a mais é N+1");
    }

    @Test
    @DisplayName("Buscar uma loja por uuid também traz o gerente de uma vez")
    void shouldFindStoreWithManagerInOneQuery() {
        Store created = storeWithManager("Loja", "Gerente");
        em.flush();
        em.clear();

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Store found = storeService.findByUuid(created.getUuid());
        assertEquals(tag + " Gerente", StoreResponse.convertToDTO(found).managerName());
        assertEquals(1, statistics.getPrepareStatementCount());
    }
}
