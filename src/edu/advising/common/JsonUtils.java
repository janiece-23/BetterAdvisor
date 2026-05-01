package edu.advising.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;

public class JsonUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonUtils() {};

    public static void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()){
            os.write(bytes);
        }
    }

    public static void sendJson(HttpExchange exchange, int statusCode, ValidationResult result) throws IOException{
       sendJson(exchange, statusCode, (Object) result);
    }

    public static ObjectMapper getMapper() {
        return MAPPER;
    }
}
