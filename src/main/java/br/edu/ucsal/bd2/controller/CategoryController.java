package br.edu.ucsal.bd2.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.edu.ucsal.bd2.dto.request.CategoryRequest;
import br.edu.ucsal.bd2.dto.response.CategoryResponse;
import br.edu.ucsal.bd2.dto.response.PageResponse;
import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.service.CategoryService;

@RestController
@RequestMapping("/api/category")
public class CategoryController {

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    // GET /api/category?page=0&size=20 (ordenado por nome; no máximo 100 por página)
    @GetMapping
    public ResponseEntity<PageResponse<CategoryResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            page = 0;
        }
        if (size < 1) {
            size = 1;
        }
        if (size > 100) {
            size = 100;
        }

        Page<Category> categoriesPage = service.findAll(PageRequest.of(page, size));

        return ResponseEntity.ok(PageResponse.from(categoriesPage, CategoryResponse::convertToDTO));
    }

    @GetMapping("/{categoryUuid}")
    public ResponseEntity<CategoryResponse> findByUuid(@PathVariable UUID categoryUuid) {
        Category category = service.findByUuid(categoryUuid);
        CategoryResponse response = CategoryResponse.convertToDTO(category);

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@RequestBody CategoryRequest request) {
        Category category = service.createCategory(request);
        CategoryResponse response = CategoryResponse.convertToDTO(category);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{categoryUuid}")
    public ResponseEntity<CategoryResponse> updateCategory(@PathVariable UUID categoryUuid,
                                                           @RequestBody CategoryRequest request) {
        Category category = service.updateCategory(categoryUuid, request);
        CategoryResponse response = CategoryResponse.convertToDTO(category);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{categoryUuid}")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID categoryUuid) {
        service.deleteCategory(categoryUuid);

        return ResponseEntity.noContent().build();
    }
}

