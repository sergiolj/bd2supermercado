package br.edu.ucsal.bd2.exceptions;

import java.util.Collection;

// Cliente pediu ?sort= por um campo que a listagem não aceita. Vira HTTP 400 no ExceptionCatcher.
public class InvalidSortException extends RuntimeException {

	public InvalidSortException(String field, Collection<String> allowedFields) {
		super("Não é possível ordenar por '" + field + "'. Campos permitidos: " + String.join(", ", allowedFields) + ".");
	}

}
