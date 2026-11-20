package br.edu.ucsal.bd2.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.edu.ucsal.bd2.dao.StaffDAO;
import br.edu.ucsal.bd2.dao.StoreDAO;
import br.edu.ucsal.bd2.dto.request.StoreRequest;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.util.AddressBuilder;
import br.edu.ucsal.bd2.util.TextRules;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

// CRUD de loja: uuid na API, soft delete e listagem paginada. O gerente é um Staff ativo e cada funcionário
// gerencia no máximo uma loja.
@Service
@AllArgsConstructor
public class StoreService {

    private final StoreDAO dao;
    private final StaffDAO staffDao;

    public Page<Store> findAll(Pageable pageable) {
        return dao.findActive(pageable);
    }

    // Loja apagada (deletedAt preenchido) é tratada como inexistente.
    public Store findByUuid(UUID storeUuid) {
        return dao.findActiveByUuid(storeUuid)
                .orElseThrow(() -> new EntityNotFoundException("Loja com uuid: " + storeUuid + " não encontrada."));
    }

    @Transactional
    public Store createStore(StoreRequest dto) {
        String name = TextRules.required(dto.name(), "Nome da loja");

        if (dao.existsByName(name)) {
            throw new EntityExistsException("Já existe uma loja com esse nome.");
        }

        Staff manager = findManager(dto.managerUuid());
        if (manager != null && dao.existsByManager(manager)) {
            throw new EntityExistsException("Esse funcionário já gerencia outra loja.");
        }

        Store store = new Store();
        fillData(store, name, manager, dto);
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
        if (manager != null && dao.existsByManagerInAnotherStore(manager, storeUuid)) {
            throw new EntityExistsException("Esse funcionário já gerencia outra loja.");
        }

        fillData(store, name, manager, dto);
        return dao.update(store);
    }

    // Soft delete: a loja continua no banco. O gerente é solto, para ele poder gerenciar outra loja e poder ser apagado.
    @Transactional
    public void deleteStore(UUID storeUuid) {
        Store store = findByUuid(storeUuid);

        store.setManager(null);
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

    private void fillData(Store store, String name, Staff manager, StoreRequest dto) {
        store.setName(name);
        store.setManager(manager);
        store.setAddress(AddressBuilder.build(dto.street(), dto.number(), dto.district(), dto.city(), dto.state(), dto.zipCode()));
    }
}
