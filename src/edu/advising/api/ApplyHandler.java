package edu.advising.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import edu.advising.auth.ApplicationService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ApplyHandler implements HttpHandler {
    private final ApplicationService applicationService = new ApplicationService();

    public void addCorsHeader(HttpExchange exchange){
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Method", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-type, Authorization");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeader(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"Success\":false, \"message\": \"Method not allowed\"}");
            return;
        }


        //parse request body
        String body = readBody(exchange);
        String firstName = extractField(body, "firstName");
        String lastName = extractField(body, "lastName");
        String email = extractField(body, "email");

        if (firstName == null || lastName == null || email == null) {
            sendJson(exchange, 400, "{\"success\":false,\"message\":\"firstName, lastName, and email are required\"}");
            return;
        }

        //delegate to ApplicationService
        ApplicationService.ApplicationResult result = applicationService.apply(firstName, lastName, email);

        if (result.isSuccess()) {
            //TODO: pass result.getToken() to email layer here
            // eg. emailService.sendAdminNotification(result.getToken());
            sendJson(exchange, 201, "{\"success\":true,\"message\":\"Application submitted successfully\"}" );
        } else {
            String error = result.getFirstError();
            String json = String.format("{\"success\":false,\"message\":\"%s\"}", escape(error));
            sendJson(exchange, 400, json);
        }
    }

    //helpers

    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException{
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try(InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String extractField(String json, String fieldName) {
        String pattern = "\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private String escape(String s){
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
