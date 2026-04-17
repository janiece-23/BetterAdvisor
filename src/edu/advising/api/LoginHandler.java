package edu.advising.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import edu.advising.auth.AuthenticationContext;
import edu.advising.auth.AuthenticationResult;
import edu.advising.auth.BasicAuthentication;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class LoginHandler implements HttpHandler {

    private final AuthenticationContext authenticationContext = new AuthenticationContext(new BasicAuthentication());

    public void addCorsHeader(HttpExchange exchange){
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeader(exchange);

        if("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())){
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if(!"POST".equalsIgnoreCase(exchange.getRequestMethod())){
            sendJson(exchange, 405, "{\"success\": false, \"message\":\"Method not allowed\"}");
            return;
        }
        //read and parse request body

        String body = readBody(exchange);
        String username = extractField(body, "username");
        String password = extractField(body, "password");

        System.out.println("body= "+ body);
        System.out.println("username= "+ username);
        System.out.println("PASSWORD= "+ password);

        if (username == null || password == null){
           sendJson(exchange, 400, "{\"success\": false, \"message\": \"username and password is required!\"}");
           return;
        }

        //Delegate the Strategy pattern
        String ipAddress = exchange.getRemoteAddress().getAddress().getHostAddress();
        AuthenticationResult result = authenticationContext.login(username, password, ipAddress);


        if (result.isFullyAuthenticated()){
            String userType = result.getUser().getUserType(); //Student or Faculty
            String fullName = result.getUser().getFullName();
            String json = String.format(
                    "{\"success\":true, \"userType\": \"%s\", \"fullName\": \"%s\", \"message\": \"%s\"}",
                    userType, fullName, result.getMessage()
            );
            sendJson(exchange, 200, json);
            System.out.println("user logged in");
        }
        else {
            String json = String.format("{\"success\": false, \"userType\": null, \"message\": \"%s\"}", result.getMessage());
            sendJson(exchange, 401, json);
        }
    }

    //Helper methods
    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()){
            os.write(bytes);
        }
    }

    private String readBody(HttpExchange exchange) throws IOException{
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String extractField(String json, String fieldName){
        // Matches: "username":"jsmith"  or  "username" : "jsmith"
        String pattern = "\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
