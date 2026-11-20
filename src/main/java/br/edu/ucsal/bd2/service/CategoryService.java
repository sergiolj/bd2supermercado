package br.edu.ucsal.bd2.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.CategoryDAO;
import br.edu.ucsal.bd2.dto.request.CategoryRequest;
import br.edu.ucsal.bd2.model.Category;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class CategoryService {

    private final CategoryDAO dao;

    public Page<Category> findAll(Pageable pageable) {
        return dao.findActive(pageable);
    }

    public Category findByUuid(UUID categoryUuid) {
        Optional<Category> category = dao.findActiveByUuid(categoryUuid);

        if (category.isEmpty()) {
            throw new EntityNotFoundException("Categoria com uuid: " + categoryUuid + " não encontrada.");
        }
        return category.get();
    }

    @Transactional
    public Category createCategory(CategoryRequest dto) {
        String name = validateName(dto.name());

        if (dao.existsByName(name)) {
            throw new EntityExistsException("Já existe uma categoria com esse nome.");
        }

        Category category = new Category(null, name);
        dao.save(category);
        return category;
    }

    @Transactional
    public Category updateCategory(UUID categoryUuid, CategoryRequest dto) {
        Category category = findByUuid(categoryUuid);
        String name = validateName(dto.name());

        if (dao.existsByNameInAnotherCategory(name, categoryUuid)) {
            throw new EntityExistsException("Já existe uma categoria com esse nome.");
        }

        category.setName(name);
        return dao.update(category);
    }

    // Soft delete: o registro continua no banco pra preservar o historico de vendas.
    @Transactional
    public void deleteCategory(UUID categoryUuid) {
        Category category = findByUuid(categoryUuid);

        if (dao.hasActiveProducts(category)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Categoria possui produtos ativos e não pode ser apagada.");
        }

        category.setDeletedAt(Instant.now());
        dao.update(category);
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome da categoria é obrigatório.");
        }
        return name.strip();
    }
}

