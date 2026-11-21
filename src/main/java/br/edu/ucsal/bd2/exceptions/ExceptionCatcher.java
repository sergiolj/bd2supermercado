package br.edu.ucsal.bd2.exceptions;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;

// Converte as exceções dos services na resposta de erro da API (StandardError). O título (error) diz o tipo do problema
// e vale para qualquer recurso; o detalhe (message) é o texto escrito pelo service, que diz qual registro e por quê.
@RestControllerAdvice
public class ExceptionCatcher {

	@ExceptionHandler(EntityNotFoundException.class)
	public ResponseEntity<StandardError> entityNotFound(EntityNotFoundException e, HttpServletRequest request) {
		return build(HttpStatus.NOT_FOUND, "Registro não encontrado.", e.getMessage(), request);
	}

	@ExceptionHandler(InvalidSortException.class)
	public ResponseEntity<StandardError> invalidSort(InvalidSortException e, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, "Ordenação inválida.", e.getMessage(), request);
	}

	@ExceptionHandler(EntityExistsException.class)
	public ResponseEntity<StandardError> entityAlreadyExists(EntityExistsException e, HttpServletRequest request) {
		return build(HttpStatus.CONFLICT, "Registro já cadastrado.", e.getMessage(), request);
	}

	// Regras de negócio dos services (400 dado inválido, 409 operação recusada pelo estado atual). Sem este handler a
	// resposta sairia no formato do Spring: com o stack trace quando o DevTools está ligado e, fora dele, sem a mensagem
	// (server.error.include-message=never), que é justamente o que explica o erro ao cliente.
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<StandardError> responseStatus(ResponseStatusException e, HttpServletRequest request) {
		String title = title(e.getStatusCode());
		return build(e.getStatusCode(), title, e.getReason() == null ? title : e.getReason(), request);
	}

	private static String title(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "Requisição inválida.";
			case 404 -> "Registro não encontrado.";
			case 409 -> "Operação não permitida no estado atual.";
			default -> {
				HttpStatus known = HttpStatus.resolve(status.value());
				yield known == null ? "Erro " + status.value() + "." : known.getReasonPhrase();
			}
		};
	}

	private static ResponseEntity<StandardError> build(HttpStatusCode status, String title, String message,
			HttpServletRequest request) {
		StandardError error = new StandardError(Instant.now(), status.value(), title, message, request.getRequestURI());
		return ResponseEntity.status(status).body(error);
	}
}
