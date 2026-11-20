package br.edu.ucsal.bd2.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
import br.edu.ucsal.bd2.util.SaleStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * As 3 consultas com Criteria API do trabalho (Ref. §4.2 item 5).
 *
 * Usa o EntityManager direto, sem os métodos automáticos do Spring Data, para mostrar a API da JPA:
 * CriteriaBuilder monta as expressões, CriteriaQuery é a consulta e Root/Join são o FROM e os JOINs.
 * A vantagem sobre JPQL em texto aparece nos filtros opcionais: cada filtro preenchido vira um Predicate
 * e só os preenchidos entram no WHERE, sem concatenar strings.
 *
 * 1) searchProducts  - produtos com filtros dinâmicos (nome, categoria, faixa de preço, unidade) + paginação
 * 2) searchSales     - vendas com filtros dinâmicos (período, vendedor, status, faixa de valor) + paginação
 * 3) revenueByCategory - faturamento por categoria num período (GROUP BY, SUM, COUNT DISTINCT, HAVING)
 */
@Service
@Transactional(readOnly = true)
public class CriteriaQueryService {

	// Mesmo fuso usado pelo seed: as datas do filtro são dias do calendário da loja.
	public static final ZoneId STORE_ZONE = ZoneId.of("America/Bahia");

	public static final int DEFAULT_PAGE_SIZE = 20;
	public static final int MAX_PAGE_SIZE = 100;

	@PersistenceContext
	private EntityManager em;

	// ------------------------------------------------------------------------------------------------
	// 1) Busca de produtos com filtros dinâmicos
	//
	// SQL equivalente (com todos os filtros preenchidos):
	//   SELECT p.uuid, p.name, p.barcode, p.unit_of_measure, c.name, pr.value_in_cents
	//     FROM price pr JOIN product p ON p.id = pr.product_id JOIN category c ON c.id = p.category_id
	//    WHERE pr.deleted_at IS NULL AND p.deleted_at IS NULL
	//      AND lower(p.name) LIKE '%arroz%' AND c.uuid = ? AND pr.value_in_cents BETWEEN ? AND ?
	//      AND p.unit_of_measure = ?
	//    ORDER BY p.name, p.id  LIMIT ? OFFSET ?
	//
	// Parte de Price porque só ele aponta para Product (Product não tem a lista de preços). O preço vigente
	// é a linha com deletedAt nulo; produto sem preço vigente não está à venda e fica fora da busca.
	// ------------------------------------------------------------------------------------------------
	public Page<ProductSearchResponse> searchProducts(ProductSearchFilter filter, int page, int size) {
		validateRange(filter.minPriceInCents(), filter.maxPriceInCents(), "minPriceInCents", "maxPriceInCents");
		PageRequest pageRequest = pageRequest(page, size);
		CriteriaBuilder cb = em.getCriteriaBuilder();

		// Consulta da página: monta o DTO direto no SELECT (cb.construct).
		CriteriaQuery<ProductSearchResponse> cq = cb.createQuery(ProductSearchResponse.class);
		Root<Price> price = cq.from(Price.class);
		Join<Price, Product> product = price.join("product");
		Join<Product, Category> category = product.join("category");

		cq.select(cb.construct(ProductSearchResponse.class,
				product.get("uuid"),
				product.get("name"),
				product.get("barcode"),
				product.get("unitOfMeasure"),
				category.get("name"),
				price.get("valueInCents")))
			.where(productPredicates(cb, filter, price, product, category))
			.orderBy(cb.asc(product.get("name")), cb.asc(product.get("id")));

		TypedQuery<ProductSearchResponse> query = em.createQuery(cq)
				.setFirstResult((int) pageRequest.getOffset())
				.setMaxResults(pageRequest.getPageSize());
		List<ProductSearchResponse> content = query.getResultList();

		// Consulta do total: mesmo FROM/WHERE, só que com COUNT. Predicates não podem ser reaproveitados
		// entre consultas (pertencem ao Root de cada uma), por isso são montados de novo.
		CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
		Root<Price> countPrice = countQuery.from(Price.class);
		Join<Price, Product> countProduct = countPrice.join("product");
		Join<Product, Category> countCategory = countProduct.join("category");
		countQuery.select(cb.count(countPrice))
			.where(productPredicates(cb, filter, countPrice, countProduct, countCategory));
		long total = em.createQuery(countQuery).getSingleResult();

		return new PageImpl<>(content, pageRequest, total);
	}

	private Predicate[] productPredicates(CriteriaBuilder cb, ProductSearchFilter filter,
			Root<Price> price, Join<Price, Product> product, Join<Product, Category> category) {
		List<Predicate> predicates = new ArrayList<>();

		// Sempre: só o preço vigente de produtos ativos (soft delete).
		predicates.add(cb.isNull(price.get("deletedAt")));
		predicates.add(cb.isNull(product.get("deletedAt")));

		// Opcionais: entram no WHERE só quando preenchidos.
		if (hasText(filter.name())) {
			predicates.add(cb.like(cb.lower(product.get("name")), "%" + filter.name().trim().toLowerCase() + "%"));
		}
		if (filter.categoryUuid() != null) {
			predicates.add(cb.equal(category.get("uuid"), filter.categoryUuid()));
		}
		if (filter.minPriceInCents() != null) {
			predicates.add(cb.greaterThanOrEqualTo(price.get("valueInCents"), filter.minPriceInCents()));
		}
		if (filter.maxPriceInCents() != null) {
			predicates.add(cb.lessThanOrEqualTo(price.get("valueInCents"), filter.maxPriceInCents()));
		}
		if (filter.unitOfMeasure() != null) {
			predicates.add(cb.equal(product.get("unitOfMeasure"), filter.unitOfMeasure()));
		}

		return predicates.toArray(Predicate[]::new);
	}

	// ------------------------------------------------------------------------------------------------
	// 2) Busca de vendas com filtros dinâmicos
	//
	// SQL equivalente (com todos os filtros preenchidos):
	//   SELECT s.uuid, s.created_at, s.status, s.total_amount_in_cents, st.name
	//     FROM sale s JOIN staff st ON st.id = s.seller_id
	//    WHERE s.created_at >= ? AND s.created_at < ? AND st.uuid = ? AND s.status = ?
	//      AND s.total_amount_in_cents BETWEEN ? AND ?
	//    ORDER BY s.created_at DESC, s.id DESC  LIMIT ? OFFSET ?
	// ------------------------------------------------------------------------------------------------
	public Page<SaleSummaryResponse> searchSales(SaleSearchFilter filter, int page, int size) {
		validateRange(filter.from(), filter.to(), "from", "to");
		validateRange(filter.minTotalInCents(), filter.maxTotalInCents(), "minTotalInCents", "maxTotalInCents");
		PageRequest pageRequest = pageRequest(page, size);
		CriteriaBuilder cb = em.getCriteriaBuilder();

		CriteriaQuery<SaleSummaryResponse> cq = cb.createQuery(SaleSummaryResponse.class);
		Root<Sale> sale = cq.from(Sale.class);
		Join<Sale, Staff> seller = sale.join("seller");

		cq.select(cb.construct(SaleSummaryResponse.class,
				sale.get("uuid"),
				sale.get("createdAt"),
				sale.get("status"),
				sale.get("totalAmountInCents"),
				seller.get("name")))
			.where(salePredicates(cb, filter, sale, seller))
			.orderBy(cb.desc(sale.get("createdAt")), cb.desc(sale.get("id")));

		List<SaleSummaryResponse> content = em.createQuery(cq)
				.setFirstResult((int) pageRequest.getOffset())
				.setMaxResults(pageRequest.getPageSize())
				.getResultList();

		CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
		Root<Sale> countSale = countQuery.from(Sale.class);
		Join<Sale, Staff> countSeller = countSale.join("seller");
		countQuery.select(cb.count(countSale))
			.where(salePredicates(cb, filter, countSale, countSeller));
		long total = em.createQuery(countQuery).getSingleResult();

		return new PageImpl<>(content, pageRequest, total);
	}

	private Predicate[] salePredicates(CriteriaBuilder cb, SaleSearchFilter filter,
			Root<Sale> sale, Join<Sale, Staff> seller) {
		List<Predicate> predicates = new ArrayList<>();

		if (filter.from() != null) {
			predicates.add(cb.greaterThanOrEqualTo(sale.<Instant>get("createdAt"), startOfDay(filter.from())));
		}
		if (filter.to() != null) {
			// "to" é inclusivo: pega tudo antes do início do dia seguinte.
			predicates.add(cb.lessThan(sale.<Instant>get("createdAt"), startOfDay(filter.to().plusDays(1))));
		}
		if (filter.sellerUuid() != null) {
			predicates.add(cb.equal(seller.get("uuid"), filter.sellerUuid()));
		}
		if (filter.status() != null) {
			predicates.add(cb.equal(sale.get("status"), filter.status()));
		}
		if (filter.minTotalInCents() != null) {
			predicates.add(cb.greaterThanOrEqualTo(sale.get("totalAmountInCents"), filter.minTotalInCents()));
		}
		if (filter.maxTotalInCents() != null) {
			predicates.add(cb.lessThanOrEqualTo(sale.get("totalAmountInCents"), filter.maxTotalInCents()));
		}

		return predicates.toArray(Predicate[]::new);
	}

	// ------------------------------------------------------------------------------------------------
	// 3) Faturamento por categoria em um período
	//
	// SQL equivalente:
	//   SELECT c.name, COUNT(DISTINCT s.id), SUM(i.quantity), SUM(i.quantity * i.unit_price_in_cents)
	//     FROM sale_items i JOIN sale s ON s.id = i.sale_id
	//     JOIN product p ON p.id = i.product_id JOIN category c ON c.id = p.category_id
	//    WHERE s.status = 'COMPLETED' AND s.created_at >= ? AND s.created_at < ?
	//    GROUP BY c.name
	//   HAVING SUM(i.quantity * i.unit_price_in_cents) >= ?
	//    ORDER BY 4 DESC
	//
	// Só vendas COMPLETED contam como faturamento (mesma regra da granularidade do DW).
	// Usa Tuple porque o SUM de quantidade × preço sai como BigDecimal e é arredondado para centavos em Java.
	// ------------------------------------------------------------------------------------------------
	public List<CategoryRevenueResponse> revenueByCategory(LocalDate from, LocalDate to, Long minRevenueInCents) {
		validateRange(from, to, "from", "to");
		CriteriaBuilder cb = em.getCriteriaBuilder();

		CriteriaQuery<Tuple> cq = cb.createTupleQuery();
		Root<SaleItem> item = cq.from(SaleItem.class);
		Join<SaleItem, Sale> sale = item.join("sale");
		Join<SaleItem, Product> product = item.join("product");
		Join<Product, Category> category = product.join("category");

		Expression<BigDecimal> quantity = item.get("quantity");
		Expression<BigDecimal> lineValue = cb.prod(quantity, cb.toBigDecimal(item.get("unitPriceInCents")));
		Expression<BigDecimal> revenue = cb.sum(lineValue);
		Expression<Long> salesCount = cb.countDistinct(sale.get("id"));
		Expression<BigDecimal> quantitySold = cb.sum(quantity);

		List<Predicate> where = new ArrayList<>();
		where.add(cb.equal(sale.get("status"), SaleStatus.COMPLETED));
		if (from != null) {
			where.add(cb.greaterThanOrEqualTo(sale.<Instant>get("createdAt"), startOfDay(from)));
		}
		if (to != null) {
			where.add(cb.lessThan(sale.<Instant>get("createdAt"), startOfDay(to.plusDays(1))));
		}

		cq.multiselect(
				category.get("name").alias("categoryName"),
				salesCount.alias("salesCount"),
				quantitySold.alias("quantitySold"),
				revenue.alias("revenue"))
			.where(where.toArray(Predicate[]::new))
			.groupBy(category.get("name"))
			.orderBy(cb.desc(revenue));

		if (minRevenueInCents != null) {
			cq.having(cb.greaterThanOrEqualTo(revenue, BigDecimal.valueOf(minRevenueInCents)));
		}

		return em.createQuery(cq).getResultList().stream()
				.map(t -> new CategoryRevenueResponse(
						t.get("categoryName", String.class),
						t.get("salesCount", Long.class),
						t.get("quantitySold", BigDecimal.class),
						t.get("revenue", BigDecimal.class).setScale(0, RoundingMode.HALF_UP).longValueExact()))
				.toList();
	}

	// ------------------------------------------------------------------------------------------------
	// Auxiliares
	// ------------------------------------------------------------------------------------------------

	private static PageRequest pageRequest(int page, int size) {
		if (page < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page não pode ser negativo.");
		}
		int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
		return PageRequest.of(page, safeSize);
	}

	private static Instant startOfDay(LocalDate date) {
		return date.atStartOfDay(STORE_ZONE).toInstant();
	}

	private static <T extends Comparable<? super T>> void validateRange(T min, T max, String minName, String maxName) {
		if (min != null && max != null && min.compareTo(max) > 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, minName + " não pode ser maior que " + maxName + ".");
		}
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
