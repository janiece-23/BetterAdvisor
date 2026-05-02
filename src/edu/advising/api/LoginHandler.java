package edu.advising.api;
import com.sun.net.httpserver.HttpExchange;
import edu.advising.auth.AuthenticationContext;
import edu.advising.auth.AuthenticationResult;
import edu.advising.auth.BasicAuthentication;
import edu.advising.common.ValidationResult;
import edu.advising.dto.LoginRequest;
import edu.advising.templates.AuthorizationTemplate;

import java.io.IOException;

public class LoginHandler extends AuthorizationTemplate<LoginRequest> {
    private final AuthenticationContext authenticationContext = new AuthenticationContext(new BasicAuthentication());

    @Override
    protected String getMethod() {
        return "POST";
    }

    @Override
    protected Class<LoginRequest> getRequestClass() {
        return LoginRequest.class;
    }

    @Override
    protected void handleRequest(HttpExchange exchange, LoginRequest request) throws IOException {
        if (request.username == null || request.password == null){
            ValidationResult error = ValidationResult.failure("Username and password are required");
            sendJson(exchange, 400, error);
            return;
        }

        String ipAddress = exchange.getRemoteAddress().getAddress().getHostAddress();

        AuthenticationResult result = authenticationContext.login(request.username, request.password, ipAddress);
        ValidationResult response;

        if (result.isFullyAuthenticated()){
            response = ValidationResult.success();
            response.setMetadata("userType", result.getUser().getUserType());
            response.setMetadata("fullName", result.getUser().getFullName());
            response.setMetadata("message", result.getMessage());
        } else{
            response = ValidationResult.failure(result.getMessage());
            sendJson(exchange, result.isFullyAuthenticated() ? 200 : 400, response);
        }
    }
}
