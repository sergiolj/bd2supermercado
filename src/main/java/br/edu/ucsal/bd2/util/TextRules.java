package br.edu.ucsal.bd2.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Regras de texto comuns aos cadastros. Sem elas, um texto maior que a coluna (varchar(255)) faria o banco recusar
// o INSERT e a API responderia 500 em vez de 400.
public final class TextRules {

    public static final int MAX_LENGTH = 255;

    private TextRules() {
    }

    // Campo obrigatório: sem espaços nas pontas, não vazio e dentro do tamanho da coluna.
    public static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " é obrigatório.");
        }
        return limited(value.strip(), label);
    }

    // Campo opcional: texto vazio vira null (a coluna fica sem valor em vez de guardar "").
    public static String optional(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return limited(value.strip(), label);
    }

    private static String limited(String value, String label) {
        if (value.length() > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + " deve ter no máximo " + MAX_LENGTH + " caracteres.");
        }
        return value;
    }
}
