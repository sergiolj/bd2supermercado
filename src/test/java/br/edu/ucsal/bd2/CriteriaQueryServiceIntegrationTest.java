package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import br.edu.ucsal.bd2.dto.filter.ProductSearchFilter;
import br.edu.ucsal.bd2.dto.filter.SaleSearchFilter;
import br.edu.ucsal.bd2.dto.response.CategoryRevenueResponse;
import br.edu.ucsal.bd2.dto.response.ProductSearchResponse;
import br.edu.ucsal.bd2.dto.response.SaleSummaryResponse;
import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.model.Price;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.model.SaleItem;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.service.CriteriaQueryService;
import br.edu.ucsal.bd2.util.MeasurementType;
import br.edu.ucsal.bd2.util.SaleStatus;
import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.EntityManager;

// Roda as consultas Criteria de verdade no PostgreSQL do .env (como o GrocerystoreApplicationTests).
// Cada teste cria os próprios dados com nomes únicos e o @Transactional desfaz tudo no fim,
// então funciona com o banco vazio ou já populado pelo seed.
@SpringBootTest
@Transactional
class CriteriaQueryServiceIntegrationTest {

	@Autowired
	private CriteriaQueryService service;

	@Autowired
	private EntityManager em;

	private final String tag = "ZZCRIT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	private final LocalDate today = LocalDate.now(CriteriaQueryService.STORE_ZONE);

	private Category category;
	private Store store;
	private Staff seller;
	private Product rice;
	private Product beans;

	@BeforeEach
	void setUp() {
		category = new Category();
		category.setName(tag + " Mercearia");
		em.persist(category);

		// O vendedor trabalha na loja das vendas (o banco confere).
		store = new Store();
		store.setName(tag + " Loja");
		em.persist(store);

		seller = new Staff();
		seller.setName(tag + " Vendedor");
		seller.setTaxNumber(String.format("%011d", Math.floorMod((long) tag.hashCode(), 100_000_000_000L)));
		seller.setRole(StaffRole.CASHIER);
		seller.setStore(store);
		em.persist(seller);

		rice = product(tag + " Arroz 5kg", "1" + tag, MeasurementType.UNIT, 2899L);
		beans = product(tag + " Feijao a granel", "2" + tag, MeasurementType.KG, 899L);

		// Preço antigo do arroz (substituído): não pode aparecer na busca.
		Price old = new Price();
		old.setProduct(rice);
		old.setValueInCents(2500L);
		old.setDeletedAt(Instant.now());
		em.persist(old);

		// Duas vendas concluídas e uma cancelada (que não conta como faturamento).
		sale(SaleStatus.COMPLETED, item(rice, "2", 2899L), item(beans, "1.500", 899L)); // 5798 + 1348,5
		sale(SaleStatus.COMPLETED, item(rice, "1", 2899L));                             // 2899
		sale(SaleStatus.CANCELED, item(rice, "3", 2899L));
		em.flush();
	}

	@Test
	@DisplayName("Criteria 1: filters products by name and returns only the current price")
	void shouldSearchProductsByNameWithCurrentPrice() {
		Page<ProductSearchResponse> page = service.searchProducts(
				new ProductSearchFilter(tag.toLowerCase() + " arroz", null, null, null, null), 0, 20);

		assertEquals(1, page.getTotalElements());
		assertEquals(rice.getUuid(), page.getContent().get(0).uuid());
		assertEquals(2899L, page.getContent().get(0).currentPriceInCents());
		assertEquals(tag + " Mercearia", page.getContent().get(0).categoryName());
	}

	@Test
	@DisplayName("Criteria 1: combines category, price range and unit filters")
	void shouldCombineProductFilters() {
		ProductSearchFilter byCategory = new ProductSearchFilter(null, category.getUuid(), null, null, null);
		assertEquals(2, service.searchProducts(byCategory, 0, 20).getTotalElements());

		ProductSearchFilter cheapKg = new ProductSearchFilter(null, category.getUuid(), 500L, 1000L, MeasurementType.KG);
		Page<ProductSearchResponse> page = service.searchProducts(cheapKg, 0, 20);
		assertEquals(1, page.getTotalElements());
		assertEquals(beans.getUuid(), page.getContent().get(0).uuid());
	}

	@Test
	@DisplayName("Criteria 1: paginates with setFirstResult/setMaxResults")
	void shouldPaginateProducts() {
		ProductSearchFilter byCategory = new ProductSearchFilter(null, category.getUuid(), null, null, null);
		Page<ProductSearchResponse> second = service.searchProducts(byCategory, 1, 1);

		assertEquals(2, second.getTotalElements());
		assertEquals(2, second.getTotalPages());
		assertEquals(1, second.getContent().size());
		assertEquals(beans.getUuid(), second.getContent().get(0).uuid()); // ordem por nome: Arroz na página 0, Feijao na 1
	}

	@Test
	@DisplayName("Criteria 2: filters sales by seller, status, period and minimum total")
	void shouldSearchSales() {
		SaleSearchFilter bySeller = new SaleSearchFilter(today, today, seller.getUuid(), null, null, null);
		assertEquals(3, service.searchSales(bySeller, 0, 20).getTotalElements());

		SaleSearchFilter completedAbove = new SaleSearchFilter(null, null, seller.getUuid(), SaleStatus.COMPLETED, 5000L, null);
		Page<SaleSummaryResponse> page = service.searchSales(completedAbove, 0, 20);
		assertEquals(1, page.getTotalElements());
		assertEquals(tag + " Vendedor", page.getContent().get(0).sellerName());

		SaleSearchFilter yesterday = new SaleSearchFilter(today.minusDays(1), today.minusDays(1), seller.getUuid(), null, null, null);
		assertEquals(0, service.searchSales(yesterday, 0, 20).getTotalElements());
	}

	@Test
	@DisplayName("Criteria 2: rejects an inverted period")
	void shouldRejectInvertedPeriod() {
		SaleSearchFilter inverted = new SaleSearchFilter(today, today.minusDays(1), null, null, null, null);
		assertThrows(ResponseStatusException.class, () -> service.searchSales(inverted, 0, 20));
	}

	@Test
	@DisplayName("Criteria 3: sums only completed sales per category")
	void shouldComputeRevenueByCategory() {
		List<CategoryRevenueResponse> rows = service.revenueByCategory(today, today, null);

		CategoryRevenueResponse mine = rows.stream()
				.filter(r -> r.categoryName().equals(tag + " Mercearia"))
				.findFirst().orElseThrow();

		assertEquals(2L, mine.salesCount());
		assertEquals(0, new BigDecimal("4.500").compareTo(mine.quantitySold())); // 2 + 1,5 + 1 (a cancelada fica fora)
		assertEquals(10046L, mine.revenueInCents()); // 5798 + 1348,5 + 2899 = 10045,5 -> arredonda para 10046
	}

	@Test
	@DisplayName("Criteria 3: HAVING removes categories below the minimum revenue")
	void shouldApplyHaving() {
		List<CategoryRevenueResponse> rows = service.revenueByCategory(today, today, 10047L);
		assertTrue(rows.stream().noneMatch(r -> r.categoryName().equals(tag + " Mercearia")));
	}

	// ---------------------------------------------------------------------------------------------

	private Product product(String name, String barcode, MeasurementType unit, long priceInCents) {
		Product product = new Product(name, category, barcode, null, unit);
		em.persist(product);

		Price price = new Price();
		price.setProduct(product);
		price.setValueInCents(priceInCents);
		em.persist(price);
		return product;
	}

	private SaleItem item(Product product, String quantity, long unitPriceInCents) {
		SaleItem item = new SaleItem();
		item.setProduct(product);
		item.setQuantity(new BigDecimal(quantity));
		item.setUnitPriceInCents(unitPriceInCents);
		return item;
	}

	private void sale(SaleStatus status, SaleItem... items) {
		Sale sale = new Sale();
		sale.setStore(store);
		sale.setSeller(seller);
		sale.setStatus(status);
		long total = 0;
		for (SaleItem item : items) {
			total += item.getQuantity().multiply(BigDecimal.valueOf(item.getUnitPriceInCents())).longValue();
		}
		sale.setTotalAmountInCents(total);
		em.persist(sale);

		for (SaleItem item : items) {
			item.setSale(sale);
			em.persist(item);
		}
	}
}
