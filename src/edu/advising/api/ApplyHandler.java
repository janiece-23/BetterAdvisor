package edu.advising.api;
import com.sun.net.httpserver.HttpExchange;
import edu.advising.auth.ApplicationService;
import edu.advising.common.ValidationResult;
import edu.advising.templates.AuthorizationTemplate;
import java.io.IOException;

public class ApplyHandler extends AuthorizationTemplate<ApplyRequest> {
    private final ApplicationService applicationService = new ApplicationService();

    @Override
    protected String getMethod() {
        return "POST";
    }

    @Override
    protected Class<ApplyRequest> getRequestClass() {
        return ApplyRequest.class;
    }

    @Override
    protected void handleRequest(HttpExchange exchange, ApplyRequest request) throws IOException {
        if (request.firstName == null || request.lastName == null || request.email == null) {
            ValidationResult error = ValidationResult.failure("First name, last name, and email are required");
            sendJson(exchange, 400, error);
            return;
        }

        ApplicationService.ApplicationResult result = applicationService.apply(request.firstName, request.lastName, request.email);

        if (result.isSuccess()) {
           ValidationResult response = ValidationResult.success();
           response.setMetadata("userId", String.valueOf(result.getUserId()));
           response.setMetadata("token", result.getToken());
           response.setMetadata("message", "Application submitted successfully. Check your email for next steps.");
           sendJson(exchange, 201, response);
        } else {
            ValidationResult response = ValidationResult.failure("Application could not be submitted.");
            for (String error : result.getErrors()){
                response.addError(error);
            }
            sendJson(exchange, 400, response);
        }
    }
}
