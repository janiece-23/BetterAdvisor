package edu.advising.core;

import com.sun.net.httpserver.HttpServer;
import edu.advising.api.LoginHandler;
import edu.advising.users.UserFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.concurrent.Executors;

public class Server {
    private static final int PORT = 8080;

    public static void main(String[] args) throws IOException {
        DatabaseManager db = DatabaseManager.getInstance();
        try {
            db.executeQuery("SELECT username FROM users", rs -> {
                System.out.println("=== USERS SEEN BY JAVA ===");
                while (rs.next()){
                    System.out.println(rs.getString("username"));
                }
                return null;
            });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
//        db.seedDatabase();

        UserFactory factory = new UserFactory();
/*
        factory.createUser("STUDENT",
                "studentAdmin",
                "SuperAdmin123!",
                "studentadmin@mycr.redwoods.edu",
                "John",
                "Doe",
                "S001"
        );
*/

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        // Handle requests at the root context ("/")
/*
        server.createContext("/api", (exchange) -> {
            String response = "Hello from Java Server!";
            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
*/

        server.createContext("/api/login", new LoginHandler()); // <-- Add the longin handler class here
        server.setExecutor(Executors.newCachedThreadPool());
//        server.setExecutor(null);
        server.start();

        System.out.printf("✓ Server running on http://localhost:%d%n", PORT);
    }
}
