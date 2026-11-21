package br.edu.ucsal.bd2.util;

import org.springframework.data.domain.PageRequest;

// Regras de paginação dos endpoints que recebem ?page= e ?size= como inteiros. Valores fora da faixa são corrigidos
// em vez de recusados: page negativo vira 0 e size fica entre 1 e MAX_SIZE.
public final class PageRules {

    public static final int MAX_SIZE = 100;

    private PageRules() {
    }

    public static PageRequest of(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_SIZE));
    }
}
