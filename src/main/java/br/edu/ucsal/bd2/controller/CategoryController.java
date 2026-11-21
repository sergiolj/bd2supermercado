package br.edu.ucsal.bd2.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
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
import br.edu.ucsal.bd2.util.PageRules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Categorias", description = "Cadastro de categorias de produtos. O nome não pode repetir. "
        + "Exclusão lógica: categoria com produtos ativos não pode ser apagada.")
@RestController
@RequestMapping("/api/category")
public class CategoryController {

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    // GET /api/category?page=0&size=20 (ordenado por nome; no máximo 100 por página)
    @Operation(summary = "Lista as categorias ativas",
            description = "Paginado e ordenado por nome. `page` começa em 0; `size` vai de 1 a 100 (padrão 20).")
    @ApiResponse(responseCode = "200", description = "Página de categorias")
    @GetMapping
    public ResponseEntity<PageResponse<CategoryResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Category> categoriesPage = service.findAll(PageRules.of(page, size));

        return ResponseEntity.ok(PageResponse.from(categoriesPage, CategoryResponse::convertToDTO));
    }

    @Operation(summary = "Busca uma categoria pelo uuid")
    @ApiResponse(responseCode = "200", description = "Categoria encontrada")
    @ApiResponse(responseCode = "404", description = "Categoria inexistente ou já apagada")
    @GetMapping("/{categoryUuid}")
    public ResponseEntity<CategoryResponse> findByUuid(@PathVariable UUID categoryUuid) {
        Category category = service.findByUuid(categoryUuid);
        CategoryResponse response = CategoryResponse.convertToDTO(category);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Cadastra uma categoria",
            description = "O nome é obrigatório e não pode repetir (nem com o de uma categoria apagada).")
    @ApiResponse(responseCode = "201", description = "Categoria criada")
    @ApiResponse(responseCode = "400", description = "Nome vazio")
    @ApiResponse(responseCode = "409", description = "Nome já usado por outra categoria")
    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@RequestBody CategoryRequest request) {
        Category category = service.createCategory(request);
        CategoryResponse response = CategoryResponse.convertToDTO(category);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Atualiza uma categoria", description = "Substitui o nome.")
    @ApiResponse(responseCode = "200", description = "Categoria atualizada")
    @ApiResponse(responseCode = "400", description = "Nome vazio")
    @ApiResponse(responseCode = "404", description = "Categoria inexistente ou já apagada")
    @ApiResponse(responseCode = "409", description = "Nome já usado por outra categoria")
    @PutMapping("/{categoryUuid}")
    public ResponseEntity<CategoryResponse> updateCategory(@PathVariable UUID categoryUuid,
                                                           @RequestBody CategoryRequest request) {
        Category category = service.updateCategory(categoryUuid, request);
        CategoryResponse response = CategoryResponse.convertToDTO(category);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Apaga uma categoria (exclusão lógica)",
            description = "Só é possível se a categoria não tiver produtos ativos.")
    @ApiResponse(responseCode = "204", description = "Categoria apagada")
    @ApiResponse(responseCode = "404", description = "Categoria inexistente ou já apagada")
    @ApiResponse(responseCode = "409", description = "Categoria ainda tem produtos ativos")
    @DeleteMapping("/{categoryUuid}")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID categoryUuid) {
        service.deleteCategory(categoryUuid);

        return ResponseEntity.noContent().build();
    }
}

