package edu.advising.templates;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import edu.advising.common.JsonUtils;
import edu.advising.common.ValidationResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public abstract class AuthorizationTemplate<T> implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        addCorsHeader(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if(!exchange.getRequestMethod().equalsIgnoreCase(getMethod())){
            JsonUtils.sendJson(exchange, 405, ValidationResult.failure("Method not allowed"));
            return;
        }

        try{
            T request = parseRequest(exchange);
            handleRequest(exchange, request);
        } catch(Exception e) {
            e.printStackTrace();
            JsonUtils.sendJson(exchange, 400, ValidationResult.failure("Invalid request body"));
        }
    }

    //Template hooks
    protected abstract String getMethod();
    protected abstract Class<T> getRequestClass();
    protected abstract void handleRequest(HttpExchange exchange, T request) throws IOException;

    protected void sendJson(HttpExchange exchange, int statusCode, ValidationResult result) throws IOException{
        JsonUtils.sendJson(exchange, statusCode, result);
    }

    private T parseRequest(HttpExchange exchange) throws IOException {
        try(InputStream is = exchange.getRequestBody()){
            return JsonUtils.getMapper().readValue(is, getRequestClass());
        }
    }

    public void addCorsHeader(HttpExchange exchange){
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-type, Authorization");
    }
}
