package br.edu.ucsal.bd2.util;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import br.edu.ucsal.bd2.exceptions.InvalidSortException;

public final class SortPolicy {

	private static final String TIE_BREAKER = "id";

	private final Set<String> allowedFields;

	private SortPolicy(Set<String> allowedFields) {
		this.allowedFields = allowedFields;
	}

	public static SortPolicy allowing(String... fields) {
		return new SortPolicy(new LinkedHashSet<>(Arrays.asList(fields)));
	}

	public Pageable apply(Pageable pageable) {
		pageable.getSort().forEach(order -> {
			if (!allowedFields.contains(order.getProperty())) {
				throw new InvalidSortException(order.getProperty(), allowedFields);
			}
		});

		if (pageable.isUnpaged()) {
			return pageable;
		}

		Sort sort = pageable.getSort();
		if (sort.getOrderFor(TIE_BREAKER) == null) {
			sort = sort.and(Sort.by(TIE_BREAKER));
		}

		return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
	}

}
