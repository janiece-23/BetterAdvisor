import edu.advising.application.ApplicationService;
import edu.advising.application.ApplicationService.ApplicationResult;

import java.util.Scanner;

public class StudentApplyTest {

    private static final ApplicationService service = new ApplicationService();

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("\n=== Application Test Menu ===");
            System.out.println("1. Happy Path");
            System.out.println("2. Invalid Email");
            System.out.println("3. Empty Name");
            System.out.println("4. Duplicate Email");
            System.out.println("5. Manual Input");
            System.out.println("0. Exit");
            System.out.print("Choice: ");

            String choice = scanner.nextLine();

            switch (choice) {
                case "1" -> runTest("John", "Doe", randomEmail());
                case "2" -> runTest("John", "Doe", "bad-email");
                case "3" -> runTest("", "", "test@example.com");
                case "4" -> {
                    String email = "dup@example.com";
                    runTest("Jane", "Doe", email);
                    runTest("Jane", "Doe", email); // run twice
                }
                case "5" -> {
                    System.out.print("First: ");
                    String f = scanner.nextLine();
                    System.out.print("Last: ");
                    String l = scanner.nextLine();
                    System.out.print("Email: ");
                    String e = scanner.nextLine();
                    runTest(f, l, e);
                }
                case "0" -> {
                    System.out.println("Done.");
                    return;
                }
                default -> System.out.println("Invalid choice.");
            }
        }
    }

    private static void runTest(String first, String last, String email) {
        System.out.println("\n--- Running Test ---");
        System.out.println("Input: " + first + " " + last + " | " + email);

        ApplicationResult result = service.apply(first, last, email);

        if (result.isSuccess()) {
            System.out.println("✅ SUCCESS");
//            System.out.println("User ID: " + result.getUserId());
            System.out.println("Token: " + result.getToken());
        } else {
            System.out.println("❌ FAILED");
            for (String err : result.getErrors()) {
                System.out.println("- " + err);
            }
        }
    }

    private static String randomEmail() {
        return "test_" + System.currentTimeMillis() + "@example.com";
    }
}