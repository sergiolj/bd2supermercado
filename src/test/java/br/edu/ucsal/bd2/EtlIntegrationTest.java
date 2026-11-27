package br.edu.ucsal.bd2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import br.edu.ucsal.bd2.dto.response.EtlRunResponse;
import br.edu.ucsal.bd2.model.Address;
import br.edu.ucsal.bd2.model.Category;
import br.edu.ucsal.bd2.model.Customer;
import br.edu.ucsal.bd2.model.Product;
import br.edu.ucsal.bd2.model.Sale;
import br.edu.ucsal.bd2.model.SaleItem;
import br.edu.ucsal.bd2.model.Staff;
import br.edu.ucsal.bd2.model.Store;
import br.edu.ucsal.bd2.service.EtlService;
import br.edu.ucsal.bd2.util.MeasurementType;
import br.edu.ucsal.bd2.util.SaleStatus;
import br.edu.ucsal.bd2.util.StaffRole;
import jakarta.persistence.EntityManager;

// Roda o ETL de verdade (procedures de db/dw/02_etl.sql) no PostgreSQL do .env. Cada teste cria as próprias vendas,
// chama o ETL na mesma transação e confere o DW; o @Transactional desfaz tudo no fim, inclusive o que o ETL gravou.
// Funciona com o banco vazio ou já populado pelo seed (as vendas do seed também são carregadas, mas os testes só
// olham as próprias).
@SpringBootTest
@Transactional
class EtlIntegrationTest {

	private static final ZoneId STORE_ZONE = ZoneId.of("America/Bahia");

	@Autowired
	private EtlService etl;

	@Autowired
	private EntityManager em;

	private final String tag = "ZZETL" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

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

		// Endereço "sujo" de propósito: o ETL padroniza espaços e a sigla da UF.
		store = new Store();
		store.setName(tag + " Loja");
		store.setAddress(address("  Feira   de Santana ", "ba"));
		em.persist(store);

		seller = new Staff();
		seller.setName(tag + " Vendedor");
		seller.setTaxNumber(taxNumber(tag + " Vendedor"));
		seller.setRole(StaffRole.CASHIER);
		seller.setStore(store);
		em.persist(seller);

		rice = product(tag + " Arroz 5kg", "1" + tag, MeasurementType.UNIT);
		beans = product(tag + " Feijao a granel", "2" + tag, MeasurementType.KG);
	}

	@Test
	@DisplayName("Loads only completed sales, one fact row per item, into the star and the snowflake")
	void shouldLoadCompletedSalesIntoBothModels() {
		Customer customer = customer(tag + " Cliente", address("Salvador", "BA"));
		Sale completed = sale(customer, SaleStatus.COMPLETED, item(rice, "2", 2899L), item(beans, "1.500", 899L));
		Sale canceled = sale(customer, SaleStatus.CANCELED, item(rice, "1", 2899L));
		Sale open = sale(customer, SaleStatus.OPEN, item(rice, "1", 2899L));
		em.flush();

		EtlRunResponse result = etl.run(false);

		assertTrue(result.fatosInseridos() >= 2);
		assertEquals(result.itensExtraidos(), result.fatosInseridos());
		assertEquals(result.fatosInseridos(), growth(result, "dw_estrela.fato_venda"));
		assertEquals(result.fatosInseridos(), growth(result, "dw_floco.fato_venda"));

		// valor_bruto = quantidade x preço, em reais: 2 x 28,99 = 57,98 e 1,5 x 8,99 = 13,485 -> 13,49
		assertEquals(new BigDecimal("71.47"), sumStar(completed));
		assertEquals(new BigDecimal("71.47"), sumSnowflake(completed));
		assertEquals(2L, factRows(completed));
		assertEquals(0L, factRows(canceled));
		assertEquals(0L, factRows(open));

		// A linha do fato aponta para as dimensões certas, com os textos padronizados e enriquecidos.
		Object[] star = (Object[]) em.createNativeQuery("""
				SELECT p.categoria, l.cidade, l.uf, l.estado, c.nome, c.cidade, t.data
				  FROM dw_estrela.fato_venda f
				  JOIN dw_estrela.dim_produto p ON p.sk_produto = f.sk_produto
				  JOIN dw_estrela.dim_loja l    ON l.sk_loja = f.sk_loja
				  JOIN dw_estrela.dim_cliente c ON c.sk_cliente = f.sk_cliente
				  JOIN dw_estrela.dim_tempo t   ON t.sk_tempo = f.sk_tempo
				 WHERE f.venda_uuid = ?1
				 LIMIT 1
				""").setParameter(1, completed.getUuid()).getSingleResult();
		assertEquals(tag + " Mercearia", star[0]);
		assertEquals("Feira de Santana", star[1]);
		assertEquals("BA", star[2]);
		assertEquals("Bahia", star[3]);
		assertEquals(tag + " Cliente", star[4]);
		assertEquals("Salvador", star[5]);
		assertEquals(LocalDate.now(STORE_ZONE), star[6]);

		// No floco, a categoria e a cidade vêm das tabelas normalizadas.
		Object[] snowflake = (Object[]) em.createNativeQuery("""
				SELECT cat.nome, ci.nome, e.uf
				  FROM dw_floco.fato_venda f
				  JOIN dw_floco.dim_produto p    ON p.sk_produto = f.sk_produto
				  JOIN dw_floco.dim_categoria cat ON cat.sk_categoria = p.sk_categoria
				  JOIN dw_floco.dim_loja l       ON l.sk_loja = f.sk_loja
				  JOIN dw_floco.dim_cidade ci    ON ci.sk_cidade = l.sk_cidade
				  JOIN dw_floco.dim_estado e     ON e.sk_estado = ci.sk_estado
				 WHERE f.venda_uuid = ?1
				 LIMIT 1
				""").setParameter(1, completed.getUuid()).getSingleResult();
		assertEquals(tag + " Mercearia", snowflake[0]);
		assertEquals("Feira de Santana", snowflake[1]);
		assertEquals("BA", snowflake[2]);
	}

	@Test
	@DisplayName("Incremental: a second run inserts nothing new, and a sale completed later enters on the next run")
	void shouldLoadIncrementally() {
		Sale sale = sale(null, SaleStatus.OPEN, item(rice, "1", 2899L));
		em.flush();
		etl.run(false);
		assertEquals(0L, factRows(sale));

		assertEquals(0, etl.run(false).fatosInseridos());

		sale.setStatus(SaleStatus.COMPLETED);
		em.flush();
		EtlRunResponse afterCompletion = etl.run(false);
		assertEquals(1, afterCompletion.fatosInseridos());
		assertEquals(1L, factRows(sale));
	}

	@Test
	@DisplayName("A sale without customer goes to the fixed 'Não identificado' member (sk 0)")
	void shouldUseUnknownCustomerMember() {
		Sale anonymous = sale(null, SaleStatus.COMPLETED, item(rice, "1", 2899L));
		em.flush();

		etl.run(false);

		Object[] row = (Object[]) em.createNativeQuery("""
				SELECT f.sk_cliente, c.nome
				  FROM dw_estrela.fato_venda f
				  JOIN dw_estrela.dim_cliente c ON c.sk_cliente = f.sk_cliente
				 WHERE f.venda_uuid = ?1
				""").setParameter(1, anonymous.getUuid()).getSingleResult();
		assertEquals(0, ((Number) row[0]).intValue());
		assertEquals("Não identificado", row[1]);
	}

	@Test
	@DisplayName("SCD type 2: a city change opens a new customer version; old sales keep the old one")
	void shouldKeepCustomerHistory() {
		Customer customer = customer(tag + " Cliente", address("Salvador", "BA"));
		Sale before = sale(customer, SaleStatus.COMPLETED, item(rice, "1", 2899L));
		em.flush();
		etl.run(false);

		customer.getAddress().setCity("Recife");
		customer.getAddress().setState("PE");
		Sale after = sale(customer, SaleStatus.COMPLETED, item(beans, "2", 899L));
		em.flush();
		EtlRunResponse result = etl.run(false);

		assertEquals(1, result.clientesNovasVersoes());

		@SuppressWarnings("unchecked")
		List<Object[]> versions = em.createNativeQuery("""
				SELECT sk_cliente, cidade, uf, atual, data_fim
				  FROM dw_estrela.dim_cliente
				 WHERE cliente_uuid = ?1
				 ORDER BY sk_cliente
				""").setParameter(1, customer.getUuid()).getResultList();
		assertEquals(2, versions.size());
		Object[] old = versions.get(0);
		Object[] current = versions.get(1);
		assertEquals("Salvador", old[1]);
		assertFalse((Boolean) old[3]);
		assertEquals(LocalDate.now(STORE_ZONE), old[4]);
		assertEquals("Recife", current[1]);
		assertEquals("PE", current[2]);
		assertTrue((Boolean) current[3]);

		assertEquals(((Number) old[0]).intValue(), customerKey(before));
		assertEquals(((Number) current[0]).intValue(), customerKey(after));

		// O floco recebe as mesmas versões.
		Number snowflakeVersions = (Number) em.createNativeQuery(
				"SELECT count(*) FROM dw_floco.dim_cliente WHERE cliente_uuid = ?1")
				.setParameter(1, customer.getUuid()).getSingleResult();
		assertEquals(2, snowflakeVersions.intValue());
	}

	@Test
	@DisplayName("Customer without address goes to 'Não informado' / NI")
	void shouldTreatMissingAddress() {
		Customer customer = customer(tag + " Sem Endereco", null);
		sale(customer, SaleStatus.COMPLETED, item(rice, "1", 2899L));
		em.flush();

		etl.run(false);

		Object[] row = (Object[]) em.createNativeQuery("""
				SELECT cidade, uf, estado FROM dw_estrela.dim_cliente WHERE cliente_uuid = ?1 AND atual
				""").setParameter(1, customer.getUuid()).getSingleResult();
		assertEquals("Não informado", row[0]);
		assertEquals("NI", row[1]);
		assertEquals("Não informado", row[2]);
	}

	@Test
	@DisplayName("Full reload: the fact has exactly the completed items of the OLTP, with the same total")
	void shouldReloadEverything() {
		sale(null, SaleStatus.COMPLETED, item(rice, "3", 2899L));
		em.flush();

		EtlRunResponse result = etl.run(true);

		assertEquals("COMPLETA", result.tipo());
		Object[] oltp = (Object[]) em.createNativeQuery("""
				SELECT count(*), sum(round(i.quantity * i.unit_price_in_cents) / 100.0)
				  FROM sale_items i JOIN sale s ON s.id = i.sale_id
				 WHERE s.status = 'COMPLETED'
				""").getSingleResult();
		Object[] star = (Object[]) em.createNativeQuery(
				"SELECT count(*), sum(valor_liquido) FROM dw_estrela.fato_venda").getSingleResult();
		Object[] snowflake = (Object[]) em.createNativeQuery(
				"SELECT count(*), sum(valor_liquido) FROM dw_floco.fato_venda").getSingleResult();

		assertEquals(((Number) oltp[0]).longValue(), ((Number) star[0]).longValue());
		assertEquals(((Number) oltp[0]).longValue(), ((Number) snowflake[0]).longValue());
		assertEquals(0, ((BigDecimal) oltp[1]).compareTo((BigDecimal) star[1]));
		assertEquals(0, ((BigDecimal) oltp[1]).compareTo((BigDecimal) snowflake[1]));
		assertEquals(result.fatosInseridos(), ((Number) star[0]).intValue());
	}

	// ---------------------------------------------------------------------------------------------

	private long growth(EtlRunResponse result, String table) {
		return result.tabelas().stream()
				.filter(t -> t.tabela().equals(table))
				.mapToLong(t -> t.depois() - t.antes())
				.findFirst().orElseThrow();
	}

	private long factRows(Sale sale) {
		return ((Number) em.createNativeQuery("SELECT count(*) FROM dw_estrela.fato_venda WHERE venda_uuid = ?1")
				.setParameter(1, sale.getUuid()).getSingleResult()).longValue();
	}

	private BigDecimal sumStar(Sale sale) {
		return (BigDecimal) em.createNativeQuery("SELECT sum(valor_liquido) FROM dw_estrela.fato_venda WHERE venda_uuid = ?1")
				.setParameter(1, sale.getUuid()).getSingleResult();
	}

	private BigDecimal sumSnowflake(Sale sale) {
		return (BigDecimal) em.createNativeQuery("SELECT sum(valor_liquido) FROM dw_floco.fato_venda WHERE venda_uuid = ?1")
				.setParameter(1, sale.getUuid()).getSingleResult();
	}

	private int customerKey(Sale sale) {
		return ((Number) em.createNativeQuery("SELECT sk_cliente FROM dw_estrela.fato_venda WHERE venda_uuid = ?1")
				.setParameter(1, sale.getUuid()).getSingleResult()).intValue();
	}

	private static Address address(String city, String state) {
		Address address = new Address();
		address.setCity(city);
		address.setState(state);
		return address;
	}

	private static String taxNumber(String name) {
		return String.format("%011d", Math.floorMod((long) name.hashCode(), 100_000_000_000L));
	}

	private Customer customer(String name, Address address) {
		Customer customer = new Customer();
		customer.setName(name);
		customer.setTaxNumber(taxNumber(name));
		customer.setAddress(address);
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

	private Sale sale(Customer customer, SaleStatus status, SaleItem... items) {
		Sale sale = new Sale();
		sale.setStore(store);
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
}
