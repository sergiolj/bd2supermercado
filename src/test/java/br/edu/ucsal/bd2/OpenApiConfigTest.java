package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import br.edu.ucsal.bd2.config.OpenApiConfig;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.tags.Tag;

class OpenApiConfigTest {

	private final OpenApiCustomizer customizer = new OpenApiConfig().sortTagsByTestFlow();

	private final OpenApiCustomizer pathCustomizer = new OpenApiConfig().sortPathsByPrecedence();

	@Test
	@DisplayName("Should list every controller tag in the order of the test flow")
	void shouldSortAllControllerTagsByTestFlow() {
		// Parte das tags de todos os controllers, embaralhadas: um controller novo que não entrar na ordem do Swagger
		// vai para o fim e quebra este teste.
		List<String> controllerTags = new ArrayList<>(controllerTagNames());
		controllerTags.sort(Comparator.reverseOrder());

		assertEquals(List.of(
				"Lojas",
				"Funcionários",
				"Categorias",
				"Produtos",
				"Preços",
				"Fornecedores",
				"Compras",
				"Clientes",
				"Vendas",
				"Consultas (Criteria API)"),
				sort(controllerTags));
	}

	@Test
	@DisplayName("Should move unknown tags to the end, keeping their relative order")
	void shouldMoveUnknownTagsToTheEnd() {
		assertEquals(List.of("Lojas", "Vendas", "Extra B", "Extra A"),
				sort(List.of("Extra B", "Vendas", "Extra A", "Lojas")));
	}

	@Test
	@DisplayName("Should accept a document without tags")
	void shouldAcceptNoTags() {
		assertDoesNotThrow(() -> customizer.customise(new OpenAPI()));
	}

	@Test
	@DisplayName("Should list a parent path before its children")
	void shouldSortParentPathBeforeChildren() {
		assertEquals(List.of(
				"/api/sale",
				"/api/sale/{saleUuid}",
				"/api/sale/{saleUuid}/cancel",
				"/api/sale/{saleUuid}/payment",
				"/api/store",
				"/api/store/{storeUuid}"),
				sortPaths(List.of(
						"/api/store/{storeUuid}",
						"/api/sale/{saleUuid}/payment",
						"/api/store",
						"/api/sale/{saleUuid}",
						"/api/sale/{saleUuid}/cancel",
						"/api/sale")));
	}

	@Test
	@DisplayName("Should compare paths segment by segment, with fixed segments before path variables")
	void shouldCompareSegmentBySegment() {
		// Comparando a string inteira, "/api/sale-item" viria antes de "/api/sale/{saleUuid}" ('-' < '/').
		assertEquals(List.of("/api/sale", "/api/sale/history", "/api/sale/{saleUuid}", "/api/sale-item"),
				sortPaths(List.of("/api/sale-item", "/api/sale/{saleUuid}", "/api/sale/history", "/api/sale")));
	}

	@Test
	@DisplayName("Should keep the operations of each path when sorting")
	void shouldKeepOperationsWhenSortingPaths() {
		PathItem store = new PathItem().get(new Operation().summary("Lista as lojas"));
		OpenAPI openApi = new OpenAPI().paths(new Paths()
				.addPathItem("/api/store/{storeUuid}", new PathItem())
				.addPathItem("/api/store", store));

		pathCustomizer.customise(openApi);

		assertSame(store, openApi.getPaths().get("/api/store"));
	}

	@Test
	@DisplayName("Should accept a document without paths")
	void shouldAcceptNoPaths() {
		assertDoesNotThrow(() -> pathCustomizer.customise(new OpenAPI()));
	}

	private List<String> sortPaths(List<String> paths) {
		Paths unsorted = new Paths();
		paths.forEach(path -> unsorted.addPathItem(path, new PathItem()));
		OpenAPI openApi = new OpenAPI().paths(unsorted);
		pathCustomizer.customise(openApi);
		return List.copyOf(openApi.getPaths().keySet());
	}

	private List<String> sort(List<String> tagNames) {
		OpenAPI openApi = new OpenAPI();
		tagNames.forEach(name -> openApi.addTagsItem(new Tag().name(name)));
		customizer.customise(openApi);
		return openApi.getTags().stream().map(Tag::getName).toList();
	}

	private static List<String> controllerTagNames() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(io.swagger.v3.oas.annotations.tags.Tag.class));
		return scanner.findCandidateComponents("br.edu.ucsal.bd2.controller").stream()
				.map(bean -> ClassUtils.resolveClassName(Objects.requireNonNull(bean.getBeanClassName()), null))
				.map(type -> AnnotatedElementUtils.findMergedAnnotation(type, io.swagger.v3.oas.annotations.tags.Tag.class))
				.map(io.swagger.v3.oas.annotations.tags.Tag::name)
				.toList();
	}
}
