package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import br.edu.ucsal.bd2.dao.JpqlQueryDAO;
import br.edu.ucsal.bd2.dto.response.CategoryMonthlyRevenueResponse;
import br.edu.ucsal.bd2.dto.response.CategoryStockFlowResponse;
import br.edu.ucsal.bd2.dto.response.InactiveCustomerResponse;
import br.edu.ucsal.bd2.dto.response.PaymentMixResponse;
import br.edu.ucsal.bd2.dto.response.PurchaseHistoryResponse;
import br.edu.ucsal.bd2.dto.response.SellerPerformanceResponse;
import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.model.Customer;
import br.edu.ucsal.bd2.model.InventoryMovement;
import br.edu.ucsal.bd2.model.Payment;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.model.SaleItem;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.service.CriteriaQueryService;
import br.edu.ucsal.bd2.util.MeasurementType;
import br.edu.ucsal.bd2.util.MovementType;
import br.edu.ucsal.bd2.util.PaymentMethod;
import br.edu.ucsal.bd2.util.PaymentStatus;
import br.edu.ucsal.bd2.util.SaleStatus;
import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.EntityManager;

// Roda as consultas JPQL 5 a 10 de verdade no PostgreSQL do .env (como o CriteriaQueryServiceIntegrationTest).
// Cada teste cria os próprios dados com nomes únicos e o @Transactional desfaz tudo no fim,
// então funciona com o banco vazio ou já populado pelo seed.
@SpringBootTest
@Transactional
class JpqlQueryDAOIntegrationTest {

	@Autowired
	private JpqlQueryDAO dao;

	@Autowired
	private EntityManager em;

	private final String tag = "ZZJPQL" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

	// Janela que cobre o dia de hoje inteiro, em qualquer fuso.
	private final Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
	private final Instant to = Instant.now().plus(1, ChronoUnit.DAYS);

	private Category category;
	private Store store;
	private Store otherStore;
	private Staff seller;
	private Staff otherSeller;
	private Staff sellerOfOtherStore;
	private Product rice;
	private Product beans;

	@BeforeEach
	void setUp() {
		category = new Category();
		category.setName(tag + " Mercearia");
		em.persist(category);

		store = store(tag + " Loja");
		otherStore = store(tag + " Outra Loja");
		seller = seller(tag + " Vendedor A", store);
		otherSeller = seller(tag + " Vendedor B", store);
		sellerOfOtherStore = seller(tag + " Vendedor da Outra", otherStore);

		rice = product(tag + " Arroz 5kg", "1" + tag, MeasurementType.UNIT);
		beans = product(tag + " Feijao a granel", "2" + tag, MeasurementType.KG);
	}

	@Test
	@DisplayName("JPQL 5: sums completed sales per category and month, with quantity x price inside SUM")
	void shouldComputeMonthlyRevenueByCategory() {
		sale(seller, null, SaleStatus.COMPLETED, item(rice, "2", 2899L), item(beans, "1.500", 899L)); // 5798 + 1348,5
		sale(seller, null, SaleStatus.COMPLETED, item(rice, "1", 2899L));                             // 2899
		sale(seller, null, SaleStatus.CANCELED, item(rice, "3", 2899L));                              // fica fora
		em.flush();

		List<CategoryMonthlyRevenueResponse> rows = dao.monthlyRevenueByCategory(from, to).stream()
				.filter(r -> r.category().equals(tag + " Mercearia"))
				.toList();

		assertEquals(1, rows.size()); // as vendas do teste são todas de hoje: um mês só
		CategoryMonthlyRevenueResponse row = rows.get(0);
		assertEquals(0, new BigDecimal("4.500").compareTo(row.quantity()));
		assertEquals(10046L, row.revenueInCents()); // 10045,5 arredondado para o centavo
		assertTrue(row.month() >= 1 && row.month() <= 12);
	}

	@Test
	@DisplayName("JPQL 6: pages the customer's completed sales and brings items and products with JOIN FETCH")
	void shouldPagePurchaseHistoryWithItems() {
		Customer customer = customer(tag + " Cliente");
		Sale first = sale(seller, customer, SaleStatus.COMPLETED, item(rice, "1", 2899L));
		Sale second = sale(seller, customer, SaleStatus.COMPLETED, item(rice, "2", 2899L), item(beans, "0.500", 899L));
		Sale third = sale(seller, customer, SaleStatus.COMPLETED, item(beans, "1", 899L));
		sale(seller, customer, SaleStatus.CANCELED, item(rice, "5", 2899L)); // cancelada não entra
		// Grava e esvazia o contexto de persistência: a consulta tem de montar as vendas a partir do banco.
		em.flush();
		em.clear();

		List<PurchaseHistoryResponse> page0 = dao.purchaseHistory(customer.getUuid(), 0, 2);
		List<PurchaseHistoryResponse> page1 = dao.purchaseHistory(customer.getUuid(), 1, 2);

		// Mais recente primeiro; a venda com 2 itens aparece uma vez só (DISTINCT), com os dois itens.
		assertEquals(List.of(third.getUuid(), second.getUuid()), page0.stream().map(PurchaseHistoryResponse::sale).toList());
		assertEquals(List.of(first.getUuid()), page1.stream().map(PurchaseHistoryResponse::sale).toList());

		PurchaseHistoryResponse withTwoItems = page0.get(1);
		assertEquals(2, withTwoItems.items().size());
		assertEquals(tag + " Loja", withTwoItems.store());
		assertTrue(withTwoItems.items().stream().anyMatch(i -> i.product().equals(tag + " Feijao a granel") && i.totalInCents() == 450L));

		assertTrue(dao.purchaseHistory(UUID.randomUUID(), 0, 10).isEmpty());
	}

	@Test
	@DisplayName("JPQL 7: ranks sellers by revenue, with average ticket, for one store or the whole network")
	void shouldComputeSellerPerformance() {
		sale(seller, null, SaleStatus.COMPLETED, item(rice, "1", 1000L));
		sale(seller, null, SaleStatus.COMPLETED, item(rice, "1", 2001L));
		sale(otherSeller, null, SaleStatus.COMPLETED, item(rice, "1", 500L));
		sale(otherSeller, null, SaleStatus.CANCELED, item(rice, "10", 1000L)); // fica fora
		sale(sellerOfOtherStore, null, SaleStatus.COMPLETED, item(rice, "1", 9000L));
		em.flush();

		List<SellerPerformanceResponse> mine = dao.sellerPerformance(store.getUuid(), from, to);
		assertEquals(2, mine.size());
		assertEquals(new SellerPerformanceResponse(tag + " Vendedor A", StaffRole.CASHIER, tag + " Loja", 2L, 3001L, 1501L),
				mine.get(0)); // média 1500,5 arredondada
		assertEquals(tag + " Vendedor B", mine.get(1).seller());
		assertEquals(1L, mine.get(1).sales());

		List<SellerPerformanceResponse> network = dao.sellerPerformance(null, from, to);
		assertTrue(network.stream().anyMatch(r -> r.seller().equals(tag + " Vendedor da Outra")));
	}

	@Test
	@DisplayName("JPQL 8: NOT EXISTS lists customers without a completed purchase since the date")
	void shouldListInactiveCustomers() {
		Customer active = customer(tag + " Ativo");
		Customer lapsed = customer(tag + " Sumido");
		Customer never = customer(tag + " Nunca Comprou");
		Customer canceledOnly = customer(tag + " So Cancelou");
		sale(seller, active, SaleStatus.COMPLETED, item(rice, "1", 2899L));
		Sale old = sale(seller, lapsed, SaleStatus.COMPLETED, item(rice, "1", 2899L));
		sale(seller, canceledOnly, SaleStatus.CANCELED, item(rice, "1", 2899L));
		em.flush();
		// created_at não é atualizável pela entidade: a venda antiga é envelhecida direto no banco.
		em.createNativeQuery("UPDATE sale SET created_at = now() - interval '200 days' WHERE id = ?1")
				.setParameter(1, old.getId())
				.executeUpdate();

		Instant since = LocalDate.now(CriteriaQueryService.STORE_ZONE).minusDays(90)
				.atStartOfDay(CriteriaQueryService.STORE_ZONE).toInstant();
		List<String> names = allInactive(since).stream()
				.map(InactiveCustomerResponse::name)
				.filter(name -> name.startsWith(tag))
				.toList();

		assertEquals(List.of(tag + " Nunca Comprou", tag + " So Cancelou", tag + " Sumido"), names); // ordem por nome
		assertFalse(names.contains(tag + " Ativo"));
	}

	@Test
	@DisplayName("JPQL 9: CASE WHEN splits purchases, sales and losses per category in one pass")
	void shouldComputeStockFlowByCategory() {
		movement(store, rice, MovementType.PURCHASE, "10");
		movement(store, rice, MovementType.SALE, "4");
		movement(store, rice, MovementType.LOSS, "1");
		movement(store, beans, MovementType.PURCHASE, "5.000");
		movement(store, beans, MovementType.LOSS, "0.250");
		movement(otherStore, rice, MovementType.PURCHASE, "100"); // outra loja: fica fora
		em.flush();

		List<CategoryStockFlowResponse> rows = dao.stockFlowByCategory(store.getUuid(), from, to);

		assertEquals(1, rows.size());
		CategoryStockFlowResponse row = rows.get(0);
		assertEquals(0, new BigDecimal("15").compareTo(row.purchased()));
		assertEquals(0, new BigDecimal("4").compareTo(row.sold()));
		assertEquals(0, new BigDecimal("1.25").compareTo(row.lost()));
		assertEquals(new BigDecimal("8.33"), row.lossRatePercent()); // 1,25 / 15
	}

	@Test
	@DisplayName("JPQL 9: without purchases the loss rate is null instead of dividing by zero")
	void shouldNotDivideByZero() {
		assertNull(new CategoryStockFlowResponse("x", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE).lossRatePercent());
	}

	@Test
	@DisplayName("JPQL 10: sums only PAID payments per store and method")
	void shouldComputePaymentMixByStore() {
		Sale sale = sale(seller, null, SaleStatus.COMPLETED, item(rice, "1", 4000L));
		payment(sale, PaymentMethod.PIX, 1000L, PaymentStatus.PAID);
		payment(sale, PaymentMethod.PIX, 500L, PaymentStatus.PAID);
		payment(sale, PaymentMethod.CREDIT_CARD, 2500L, PaymentStatus.PAID);
		payment(sale, PaymentMethod.CASH, 300L, PaymentStatus.REFUNDED); // estornado: fica fora
		em.flush();

		List<PaymentMixResponse> mine = dao.paymentMixByStore(from, to).stream()
				.filter(r -> r.store().equals(tag + " Loja"))
				.toList();

		assertEquals(List.of(
				new PaymentMixResponse(tag + " Loja", PaymentMethod.CREDIT_CARD, 1L, 2500L),
				new PaymentMixResponse(tag + " Loja", PaymentMethod.PIX, 2L, 1500L)), mine);
	}

	// ---------------------------------------------------------------------------------------------

	// O seed tem muitos clientes: percorre as páginas até o fim, como um cliente da API faria.
	private List<InactiveCustomerResponse> allInactive(Instant since) {
		List<InactiveCustomerResponse> all = new ArrayList<>();
		for (int page = 0; ; page++) {
			List<InactiveCustomerResponse> rows = dao.inactiveCustomers(since, page, 100);
			all.addAll(rows);
			if (rows.size() < 100) {
				return all;
			}
		}
	}

	private Store store(String name) {
		Store store = new Store();
		store.setName(name);
		em.persist(store);
		return store;
	}

	// CPF com 11 dígitos derivado do nome, para não repetir entre as pessoas do teste.
	private static String taxNumber(String name) {
		return String.format("%011d", Math.floorMod((long) name.hashCode(), 100_000_000_000L));
	}

	private Staff seller(String name, Store store) {
		Staff seller = new Staff();
		seller.setName(name);
		seller.setTaxNumber(taxNumber(name));
		seller.setRole(StaffRole.CASHIER);
		seller.setStore(store);
		em.persist(seller);
		return seller;
	}

	private Customer customer(String name) {
		Customer customer = new Customer();
		customer.setName(name);
		customer.setTaxNumber(taxNumber(name));
		em.persist(customer);
		return customer;
	}

	private Product product(String name, String barcode, MeasurementType unit) {
		Product product = new Product(name, category, barcode, null, unit);
		em.persist(product);
		return product;
	}

	private SaleItem item(Product product, String quantity, long unitPriceInCents) {
		SaleItem item = new SaleItem();
		item.setProduct(product);
		item.setQuantity(new BigDecimal(quantity));
		item.setUnitPriceInCents(unitPriceInCents);
		return item;
	}

	private Sale sale(Staff seller, Customer customer, SaleStatus status, SaleItem... items) {
		Sale sale = new Sale();
		sale.setStore(seller.getStore());
		sale.setSeller(seller);
		sale.setCustomer(customer);
		sale.setStatus(status);
		long total = 0;
		for (SaleItem item : items) {
			total += item.totalInCents();
		}
		sale.setTotalAmountInCents(total);
		em.persist(sale);

		for (SaleItem item : items) {
			item.setSale(sale);
			em.persist(item);
		}
		return sale;
	}

	private void movement(Store store, Product product, MovementType type, String quantity) {
		InventoryMovement movement = new InventoryMovement();
		movement.setStore(store);
		movement.setProduct(product);
		movement.setType(type);
		movement.setQuantity(new BigDecimal(quantity));
		em.persist(movement);
	}

	private void payment(Sale sale, PaymentMethod method, long amountInCents, PaymentStatus status) {
		Payment payment = new Payment();
		payment.setSale(sale);
		payment.setMethod(method);
		payment.setAmountInCents(amountInCents);
		payment.setStatus(status);
		payment.setPaidAt(Instant.now());
		em.persist(payment);
	}
}
