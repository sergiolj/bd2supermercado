package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dao.CategoryDAO;
import br.edu.ucsal.bd2.dto.request.CategoryRequest;
import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.service.CategoryService;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;

// Teste de unidade puro: sem Spring e sem banco, só o service com o DAO simulado.
class CategoryServiceTest {

    private final CategoryDAO dao = mock(CategoryDAO.class);
    private final CategoryService service = new CategoryService(dao);

    @Test
    @DisplayName("Deve salvar a categoria com o nome sem espaços extras")
    void shouldCreateCategory() {
        when(dao.existsByName("Bebidas")).thenReturn(false);

        Category result = service.createCategory(new CategoryRequest("  Bebidas  "));

        assertEquals("Bebidas", result.getName());
        verify(dao).save(result);
    }

    @Test
    @DisplayName("Não deve salvar quando o nome já existe")
    void shouldRejectDuplicateName() {
        when(dao.existsByName("Bebidas")).thenReturn(true);

        assertThrows(EntityExistsException.class,
                () -> service.createCategory(new CategoryRequest("Bebidas")));

        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 400 quando o nome está vazio")
    void shouldRejectBlankName() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createCategory(new CategoryRequest("   ")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("Deve retornar 404 quando a categoria não existe")
    void shouldNotFindCategory() {
        UUID uuid = UUID.randomUUID();
        when(dao.findActiveByUuid(uuid)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.findByUuid(uuid));
    }

    @Test
    @DisplayName("Não deve apagar categoria que ainda tem produtos ativos")
    void shouldBlockDeleteWithActiveProducts() {
        UUID uuid = UUID.randomUUID();
        Category category = new Category(null, "Bebidas");
        when(dao.findActiveByUuid(uuid)).thenReturn(Optional.of(category));
        when(dao.hasActiveProducts(category)).thenReturn(true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.deleteCategory(uuid));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertNull(category.getDeletedAt());
    }

    @Test
    @DisplayName("Deve preencher deletedAt em vez de apagar o registro (soft delete)")
    void shouldSoftDeleteCategory() {
        UUID uuid = UUID.randomUUID();
        Category category = new Category(null, "Bebidas");
        when(dao.findActiveByUuid(uuid)).thenReturn(Optional.of(category));
        when(dao.hasActiveProducts(category)).thenReturn(false);

        service.deleteCategory(uuid);

        assertNotNull(category.getDeletedAt());
        verify(dao).update(category);
    }
}