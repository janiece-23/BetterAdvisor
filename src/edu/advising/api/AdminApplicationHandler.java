package edu.advising.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

public class AdminApplicationHandler implements HttpHandler {
    // TODO: replace with real ID from session once auth is setup
    public void addCorsHeaders(HttpExchange exchange){}



    @Override
    public void handle(HttpExchange exchange) throws IOException {

    }

    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException{}
    private String readBody(HttpExchange exchange) throws IOException {}
    private String extractField(String json, String fieldName){}
    private String escape(String s){}
}
