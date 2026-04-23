package edu.advising.application;

import edu.advising.common.ValidationResult;
import edu.advising.core.DatabaseManager;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Base64;


public class ApplicationService {
    private static final int VALID_TOKEN_DAYS = 7;

    private final DatabaseManager dbManager;

    public ApplicationService() {
        this.dbManager = DatabaseManager.getInstance();
    }


//    PUBLIC API

    /*
    * This validated the form and if clean, saves the pending application
    *
    * @param firstName          == Applicant's first name
    * @param lastName           == Applicant's last name
    * @param email              == Applicant's email address
    * @return ApplicationResult == always checks isSuccess() first
    * */

    public ApplicationResult apply(String firstName, String lastName, String email){
        //Validate
        ValidationResult validation = validate(firstName, lastName, email);
        if (!validation.isValid()) return ApplicationResult.fail(validation);

        String cleanFirstName = firstName.trim();
        String cleanLastName = lastName.trim();
        String cleanEmail = email.trim().toLowerCase();

        try{
//            Insert pending user row (no username/password yet)
            String userSql = "INSERT INTO users " + "(username, password, user_type, first_name, last_name, email, is_active)" + "VALUES (?, ?, 'STUDENT', ?, ?, ?, FALSE)";
            String usernamePlaceholder = "pending_" + cleanEmail;
            String passwordPlaceholder = "LOCKED";
            int userId = dbManager.executeInsert(userSql,
                    usernamePlaceholder,
                    passwordPlaceholder,
                    cleanFirstName,
                    cleanLastName,
                    cleanEmail);

            if (userId <= 0) return ApplicationResult.fail("Application could not be saved. Please Try again");

//            Generate a secure token
            String token = generateToken();
            LocalDateTime expireToken = LocalDateTime.now().plusDays(VALID_TOKEN_DAYS);

//            Persist the Token via ORM
            ApplicationToken appToken = new ApplicationToken(userId, token, expireToken);
            dbManager.upsert(appToken);

            System.out.printf(":) Application summited: %s %s <%s> | token %s%n", cleanFirstName, cleanLastName, cleanEmail, token);
            return ApplicationResult.success(userId, token);

        } catch (SQLException | IllegalAccessException e){
            String msg = e.getMessage() != null ? e.getMessage() : "";
            
            if (msg.toLowerCase().contains("unique")){
                return ApplicationResult.fail("an application with that email already exists.");
            }

            System.err.println("x Application error: " + msg);
            
            return ApplicationResult.fail("A system error occurred. Please try again layer.");
        }
    }
    public ValidationResult validate(String firstName, String lastName, String email){
        ValidationResult result = ValidationResult.success();

        if (firstName == null || firstName.isBlank()) {
            result.addError("first name is required.");
        } else if (firstName.trim().length() < 2) {
            result.addError("First name must be at least 2 characters");
        } else if (!firstName.trim().matches("[a-zA-z\\s'\\-]+")) {
            result.addError("First name contains in valid characters");
        }

        if (lastName == null || lastName.isBlank()) {
            result.addError("first name is required.");
        } else if (lastName.trim().length() < 2) {
            result.addError("First name must be at least 2 characters");
        } else if (!lastName.trim().matches("[a-zA-z\\s'\\-]+")) {
            result.addError("First name contains in valid characters");
        }

        if (email == null || email.isBlank()) {
            result.addError("Email is required");
        }else if (!email.trim().matches("^[\\w._%+\\-]+@[\\w.\\-]+\\.[a-zA-Z]{2,}$")){
            //POTENTIAL BUG: WEIRD PASTE FORMAT
            result.addError("Please enter a valid Email address.");
        } else if (isEmailTaken(email.trim().toLowerCase())){
            result.addError("An application or account with that email already exists");
        }

        return result;
    }
    public boolean isEmailTaken(String email){
        String sql = "SELECT COUNT(*) FROM users WHERE email = ?";
        try{
            return dbManager.executeQuery(sql, rs -> {
                rs.next();
                return rs.getInt(1) > 0;
            }, email);
        } catch (SQLException e) {
            System.err.println("Email Uniqueness failed: " + e.getMessage());
            return false;
        }
    }
    public String generateToken(){
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static class ApplicationResult{
        private final boolean success;
        private final int userId;
        private final String token;
        private final ValidationResult validation;
        private final String errorMessage;

        private ApplicationResult(int userId, String token){
            this.success = true;
            this.userId = userId;
            this.token = token;
            this.validation = null;
            this.errorMessage = null;
        }

        private ApplicationResult(ValidationResult validation){
            this.success = false;
            this.userId = -1;
            this.token = null;
            this.validation = validation;
            this.errorMessage = null;
        }

        private ApplicationResult(String errMessage){
            this.success = false;
            this.userId = -1;
            this.token = null;
            this.validation = null;
            this.errorMessage = errMessage;
        }

        static ApplicationResult success(int userId, String token){
            return new ApplicationResult(userId, token);
        }

        static ApplicationResult fail(ValidationResult v){
            return new ApplicationResult(v);
        }

        static ApplicationResult fail(String msg){
            return new ApplicationResult(msg);
        }
        public boolean isSuccess(){ return  success; }
        public int getuserId(){ return  userId; }
        public String getToken(){ return token; }
        public java.util.List<String> getErrors(){
            if (errorMessage != null) return java.util.List.of(errorMessage);
            if (validation != null) return validation.getErrors();
            return java.util.List.of();
        }
        public String getFirstError(){
            java.util.List<String> errs = getErrors();
            return errs.isEmpty() ? null : errs.get(0);
        }
    }
}
