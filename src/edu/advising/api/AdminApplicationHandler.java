package edu.advising.api;

import com.sun.net.httpserver.HttpExchange;
import edu.advising.common.JsonUtils;
import edu.advising.templates.AuthorizationTemplate;
import org.jdbi.v3.core.Jdbi;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;

public class AdminApplicationHandler extends AuthorizationTemplate<AdminApplicationHandler.EmptyRequest> {
    public static class EmptyRequest{}

    public final Jdbi jdbi;

    public AdminApplicationHandler(DataSource dataSource) {
        this.jdbi = Jdbi.create(dataSource);
    }

    @Override
    protected String getMethod() {
        return "GET";
    }

    @Override
    protected Class<EmptyRequest> getRequestClass() {
        return EmptyRequest.class;
    }

    @Override
    protected void handleRequest(HttpExchange exchange, EmptyRequest request) throws IOException {
        ApplicationStatus status = parsStatus(exchange.getRequestURI().getQuery());
        List<ApplicationSummary> applications = fetchApplications(status);
        JsonUtils.sendJson(exchange, 200, applications);
    }

    private ApplicationStatus parsStatus(String query) {
        if (query == null) return ApplicationStatus.PENDING;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);

            if (kv.length == 2 && "status".equalsIgnoreCase(kv[0])){
                return ApplicationStatus.from(kv[1]);
            }
        }
        return ApplicationStatus.PENDING;
    }
    private List<ApplicationSummary> fetchApplications(ApplicationStatus status) {
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery((buildSql(status)));
            return query.mapToBean(ApplicationSummary.class).list();
        });
    }

    private String buildSql(ApplicationStatus status) {
        String base = "SELECT id, first_name, AS firstname, last_name AS lastName, email, crated_at AS createdAt FROM users WHERE user_type = 'STUDENT'";

        return switch(status) {
            case PENDING -> base + " AND is_active = FALSE ORDER BY created_at DESC";
            case ACTIVE -> base + " AND is_active = TRUE ORDER BY created_at DESC";
            case ALL -> base + "ORDER BY created_at DESC";
        };
    }
}
