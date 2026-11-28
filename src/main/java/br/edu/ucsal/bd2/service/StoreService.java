package br.edu.ucsal.bd2.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.StaffDAO;
import br.edu.ucsal.bd2.dao.StoreDAO;
import br.edu.ucsal.bd2.dto.request.StoreRequest;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.util.AddressBuilder;
import br.edu.ucsal.bd2.util.TextRules;
import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

// CRUD de loja: uuid na API, soft delete e listagem paginada. O gerente é um funcionário ativo da própria loja
// (o banco garante com uma FK composta), então cada funcionário gerencia no máximo uma loja.
@Service
@AllArgsConstructor
public class StoreService {

    private final StoreDAO dao;
    private final StaffDAO staffDao;

    public Page<Store> findAll(Pageable pageable) {
        return dao.findActive(pageable);
    }

    // Loja apagada (deletedAt preenchido) é tratada como inexistente. Também resolve o header X-Store-Id nos outros services.
    public Store findByUuid(UUID storeUuid) {
        return dao.findActiveByUuid(storeUuid)
                .orElseThrow(() -> new EntityNotFoundException("Loja com uuid: " + storeUuid + " não encontrada."));
    }

    // A loja nasce sem gerente: o gerente precisa trabalhar nela, e uma loja nova ainda não tem funcionários.
    @Transactional
    public Store createStore(StoreRequest dto) {
        String name = TextRules.required(dto.name(), "Nome da loja");

        if (dao.existsByName(name)) {
            throw new EntityExistsException("Já existe uma loja com esse nome.");
        }
        if (dto.managerUuid() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Uma loja nova ainda não tem funcionários, então não pode ser criada com gerente. Cadastre a loja, "
                            + "depois o funcionário nela e então defina o gerente atualizando a loja.");
        }

        Store store = new Store();
        fillData(store, name, null, dto);
        dao.save(store);
        return store;
    }

    @Transactional
    public Store updateStore(UUID storeUuid, StoreRequest dto) {
        Store store = findByUuid(storeUuid);
        String name = TextRules.required(dto.name(), "Nome da loja");

        if (dao.existsByNameInAnotherStore(name, storeUuid)) {
            throw new EntityExistsException("Já existe uma loja com esse nome.");
        }

        Staff manager = findManager(dto.managerUuid());

        if (manager != null && manager.getRole() != StaffRole.MANAGER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O gerente da loja precisa ter o cargo MANAGER.");
        }

        if (manager != null && !worksAt(manager, store)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O funcionário '" + manager.getName() + "' não trabalha nessa loja: o gerente precisa ser funcionário da própria loja.");
        }

        fillData(store, name, manager, dto);
        return dao.update(store);
    }

    // Soft delete: a loja continua no banco com o histórico (vendas, estoque). Enquanto tiver funcionários ativos ela não
    // pode sumir, senão eles ficariam lotados numa loja inexistente; isso inclui o gerente, que é funcionário dela.
    @Transactional
    public void deleteStore(UUID storeUuid) {
        Store store = findByUuid(storeUuid);

        long activeStaff = staffDao.countActiveByStore(store);
        if (activeStaff > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A loja tem " + activeStaff + " funcionário(s) ativo(s). Transfira-os para outra loja ou apague-os antes.");
        }

        store.setDeletedAt(Instant.now());
        dao.update(store);
    }

    // Sem uuid = loja sem gerente. Com uuid, o funcionário precisa existir e estar ativo.
    private Staff findManager(UUID managerUuid) {
        if (managerUuid == null) {
            return null;
        }
        return staffDao.findActiveByUuid(managerUuid)
                .orElseThrow(() -> new EntityNotFoundException("Funcionário com uuid: " + managerUuid + " não encontrado."));
    }

    private static boolean worksAt(Staff staff, Store store) {
        return staff.getStore() != null && staff.getStore().getUuid().equals(store.getUuid());
    }

    private void fillData(Store store, String name, Staff manager, StoreRequest dto) {
        store.setName(name);
        store.setManager(manager);
        store.setAddress(AddressBuilder.build(dto.street(), dto.number(), dto.district(), dto.city(), dto.state(), dto.zipCode()));
    }
}
