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
import br.edu.ucsal.bd2.dto.request.StaffRequest;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.util.AddressBuilder;
import br.edu.ucsal.bd2.util.StaffRole;
import br.edu.ucsal.bd2.util.TextRules;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

// CRUD de funcionário no mesmo padrão do CustomerService: uuid na API, soft delete e listagem paginada.
// Todo funcionário trabalha numa loja ativa, menos o ADMIN, que é da rede e não tem loja.
@Service
@AllArgsConstructor
public class StaffService {

    private final StaffDAO dao;
    private final StoreService storeService;

    // Sem loja, lista a rede inteira (inclusive o ADMIN); com loja, só quem trabalha nela.
    public Page<Staff> findAll(UUID storeUuid, Pageable pageable) {
        if (storeUuid == null) {
            return dao.findActive(pageable);
        }
        return dao.findActiveByStore(storeService.findByUuid(storeUuid), pageable);
    }

    // Funcionário apagado (deletedAt preenchido) é tratado como inexistente.
    public Staff findByUuid(UUID staffUuid) {
        return dao.findActiveByUuid(staffUuid)
                .orElseThrow(() -> new EntityNotFoundException("Funcionário com uuid: " + staffUuid + " não encontrado."));
    }

    @Transactional
    public Staff createStaff(StaffRequest dto) {
        String taxNumber = normalizeTaxNumber(dto.taxNumber());

        if (dao.existsByTaxNumber(taxNumber)) {
            throw new EntityExistsException("Já existe um funcionário ou cliente com esse CPF.");
        }

        Store store = resolveStore(dto);

        Staff staff = new Staff();
        staff.setTaxNumber(taxNumber);
        fillData(staff, store, dto);
        dao.save(staff);
        return staff;
    }

    @Transactional
    public Staff updateStaff(UUID staffUuid, StaffRequest dto) {
        Staff staff = findByUuid(staffUuid);
        String taxNumber = normalizeTaxNumber(dto.taxNumber());

        if (dao.existsByTaxNumberInAnotherPerson(taxNumber, staffUuid)) {
            throw new EntityExistsException("Já existe um funcionário ou cliente com esse CPF.");
        }

        Store store = resolveStore(dto);

        // O gerente precisa trabalhar na loja que gerencia. A conferência vem antes de mexer no funcionário, para a
        // consulta não levar ao banco (auto flush) uma transferência que a FK composta recusaria.
        dao.findManagedStore(staff).ifPresent(managed -> {
            if (dto.role() != StaffRole.MANAGER
                    || store == null
                    || !managed.getUuid().equals(store.getUuid())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Funcionário é gerente da loja '" + managed.getName()
                                + "' e deve continuar como MANAGER nela. Troque o gerente antes de alterar o cargo ou a loja.");
            }
        });

        staff.setTaxNumber(taxNumber);
        fillData(staff, store, dto);
        return dao.update(staff);
    }

    // Soft delete: o funcionário continua no banco pra preservar o histórico de vendas.
    @Transactional
    public void deleteStaff(UUID staffUuid) {
        Staff staff = findByUuid(staffUuid);

        if (dao.managesStore(staff)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Funcionário é gerente de uma loja e não pode ser apagado. Troque o gerente da loja antes.");
        }

        staff.setDeletedAt(Instant.now());
        dao.update(staff);
    }

    // O ADMIN é da rede e não tem loja; os outros perfis precisam de uma loja ativa (o banco confere com um CHECK).
    private Store resolveStore(StaffRequest dto) {
        if (dto.role() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Perfil do funcionário é obrigatório.");
        }

        if (dto.role() == StaffRole.ADMIN) {
            if (dto.storeUuid() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "O ADMIN é da rede e não fica ligado a uma loja: deixe storeUuid vazio.");
            }
            return null;
        }

        if (dto.storeUuid() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loja é obrigatória para o perfil " + dto.role() + ".");
        }
        return storeService.findByUuid(dto.storeUuid());
    }

    private void fillData(Staff staff, Store store, StaffRequest dto) {
        staff.setName(TextRules.required(dto.name(), "Nome do funcionário"));
        staff.setRole(dto.role());
        staff.setStore(store);
        staff.setEmail(TextRules.optional(dto.email(), "E-mail"));
        staff.setPhone(TextRules.optional(dto.phone(), "Telefone"));
        staff.setAddress(AddressBuilder.build(dto.street(), dto.number(), dto.district(), dto.city(), dto.state(), dto.zipCode()));
    }

    // Aceita "123.456.789-09" ou "12345678909" e guarda só os números.
    private String normalizeTaxNumber(String taxNumber) {
        String digits = taxNumber == null ? "" : taxNumber.replaceAll("[.\\-\\s]", "");
        if (!digits.matches("\\d{11}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CPF deve ter 11 dígitos.");
        }
        return digits;
    }
}
