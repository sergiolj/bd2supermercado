package br.edu.ucsal.bd2.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Escreve no log, quando a aplicação termina de subir, onde acessar o Swagger e o banco.
 *
 * No Docker os endereços de dentro do container (postgres:5432, porta 8080 interna) não são os que se usa
 * da máquina; por isso o docker-compose.yml informa PUBLIC_API_URL e PUBLIC_DB_URL. Fora do Docker elas
 * não existem e o resumo usa a porta real do servidor e a URL do datasource.
 */
@Component
public class StartupSummary {

	private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);
	private static final String LINE = "-".repeat(72);

	private final Environment env;
	private final String publicApiUrl;
	private final String dbUrl;
	private final String dbUser;

	public StartupSummary(Environment env,
			@Value("${PUBLIC_API_URL:}") String publicApiUrl,
			@Value("${PUBLIC_DB_URL:${spring.datasource.url}}") String dbUrl,
			@Value("${spring.datasource.username:}") String dbUser) {
		this.env = env;
		this.publicApiUrl = publicApiUrl;
		this.dbUrl = dbUrl;
		this.dbUser = dbUser;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void logSummary() {
		StringBuilder summary = new StringBuilder("\n").append(LINE).append('\n')
				.append("  grocerystore no ar\n");

		// Sem servidor web (ex.: ./mvnw -Pseed) não há Swagger para mostrar.
		String port = env.getProperty("local.server.port");
		if (port != null) {
			String baseUrl = publicApiUrl.isBlank() ? "http://localhost:" + port : publicApiUrl;
			String contextPath = env.getProperty("server.servlet.context-path", "");
			summary.append("  Swagger: ").append(baseUrl).append(contextPath).append("/swagger-ui/index.html\n");
		}

		// A query da URL fica de fora porque poderia conter a senha (?password=...).
		summary.append("  Banco:   ").append(dbUrl.replaceFirst("\\?.*$", ""))
				.append("  (usuário ").append(dbUser).append(", senha do .env)\n")
				.append(LINE);

		log.info(summary.toString());
	}
}
