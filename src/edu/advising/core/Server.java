package edu.advising.core;

import com.sun.net.httpserver.HttpServer;
import edu.advising.api.LoginHandler;

import java.io.IOException;
import java.net.InetSocketAddress;

public class Server {
    private static final int PORT = 8080;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/login", new LoginHandler()); // <-- Add the longin handler class here
        server.start();

    }
}
