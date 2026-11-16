package br.edu.ucsal.bd2.dto.response;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

// Envelope de paginação reutilizável: serve para qualquer entidade, basta informar como converter cada item em DTO.
// Não expõe o Page do Spring Data de propósito: o formato do JSON fica sob nosso controle e não muda com o framework.
// "page" começa em 0, igual ao parâmetro ?page= da requisição.
public record PageResponse<T>(
		List<T> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean first,
		boolean last
) {

	public static <E, T> PageResponse<T> from(Page<E> page, Function<? super E, ? extends T> mapper) {
		List<T> content = page.getContent().stream()
				.<T>map(mapper)
				.toList();

		return new PageResponse<>(
				content,
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages(),
				page.isFirst(),
				page.isLast()
				);
	}

}
