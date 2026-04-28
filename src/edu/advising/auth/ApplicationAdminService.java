package edu.advising.auth;

import edu.advising.common.ValidationResult;
import edu.advising.core.DatabaseManager;
import edu.advising.users.User;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;

public class ApplicationAdminService {
    private final DatabaseManager dbManager;

    public ApplicationAdminService() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public List<PendingApplication> getPendingApplication() {
        String sql =
                "SELECT u.id, u.first_name, u.last_name, u.email, " +
                        "at.token, at.created_at, at.expires_at " +
                        "FROM users u " +
                        "JOIN application_tokens at ON u.id = at.user_id " +
                        "WHERE at.status = 'PENDING' " +
                        "AND at.expires_at > CURRENT_TIMESTAMP " +
                        "ORDER BY at.created_at ASC";

        try {
            return dbManager.fetchList(sql, rs -> new PendingApplication(
                            rs.getInt("id"),
                            rs.getString("first_name"),
                            rs.getString("last_name"),
                            rs.getString("email"),
                            rs.getString("token"),
                            rs.getTimestamp("created_at").toLocalDateTime(),
                            rs.getTimestamp("expires_at").toLocalDateTime()
                    )

            );
        } catch (SQLException e) {
            System.err.println("x Could not load pending application: " + e.getMessage());
            return List.of();
        }
    }

    public ApprovalResult approves(String token, int adminId) {
//        look up and validate token
        ApplicationToken appToken = findToken(token);
        if (appToken == null) {
            return ApprovalResult.fail("Application not found");
        }
        if (!appToken.isPending()){
            return ApprovalResult.fail("This application has already been " + appToken.getStatus().toLowerCase() + ".");
        }
        if (!appToken.isExpired()) {
            return ApprovalResult.fail("This application link has expired. Ask the student to reapply.");
        }

//      load the pending user
        User pendingUser = loadUser(appToken.getUserId());
        if (pendingUser == null) {
            return ApprovalResult.fail("Applicant record not found.");
        }

        try {
//            Generate Credentials
            String username = generateUserName(pendingUser.getFirstName(), pendingUser.getLastName());
            String studentId = generateStudentId();
            String tempPw = generateCompliantPassword();

//            Update users row: Real username, password, activate
            String updateUser = "UPDATE users SET username = ?, password = ?, is_active = TRUE" + "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            dbManager.executeUpdate(updateUser, username, tempPw, pendingUser.getId());

//            Insert student row
            String insertStudent = "INSERT INTO students (id, student_id, gpa, enrollment_status, academic_standing) " + "VALUES (?, ?, 0.00, 'ACTIVE', 'GOOD_STANDING')";
            dbManager.executeUpdate(insertStudent, pendingUser.getId(), studentId);

//            Mark token APPROVED
            markToken(appToken, "APPROVED", adminId);

            System.out.printf("=) Approved: %s %s | username=%s | studentId=%s%n",
                    pendingUser.getFirstName(), pendingUser.getLastName(), username, studentId);

            return ApprovalResult.success(
                    pendingUser.getEmail(),
                    pendingUser.getFirstName(),
                    username, studentId, tempPw
            );

        } catch (SQLException e) {
            System.err.println("x Approval failed: " + e.getMessage());
            return ApprovalResult.fail("A system error occurred during approval.");
        }
    }

    public boolean deny(String token, int adminId) {
        ApplicationToken appToken = findToken(token);
        if (appToken == null || !appToken.isPending()) {
            System.out.println("x Deny failed: token not found or already actioned.");
            return false;
        }

        try{
            markToken(appToken, "DENIED", adminId);
            System.out.printf("x Application denied | token=%s | adminId=%d%n", token, adminId);
            return true;
        } catch (SQLException e) {
            System.err.println("x Deny Error: " + e.getMessage());
            return false;
        }

    }

    private ApplicationToken findToken(String token) {
        String sql =
                "SELECT id, user_id, token, status, created_at, expires_at, actioned_at, actioned_by" +
                        "FROM application_token WHERE token =?";

        try{
            return dbManager.executeQuery(sql, rs -> {
                if(!rs.next()) return null;

                ApplicationToken t = new ApplicationToken(rs.getInt("user_id"), rs.getString("token"), rs.getTimestamp("expires_at").toLocalDateTime());
                t.setId(rs.getInt("id"));
                t.setStatus(rs.getString("status"));
                t.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());

                if (rs.getTimestamp("actioned_at") != null) {
                    t.setActionedAt(rs.getTimestamp("actioned_at").toLocalDateTime());
                }
                t.setActionedBy(rs.getInt("actioned_by"));
                return t;
            }, token);
        }catch (SQLException e){
            System.err.println("x Token lookup failed" + e.getMessage());
            return null;
        }
    }

    private User loadUser(int userId) {
        String sql = "SELECT id, first_name, last_name, email, FROM users WHERE id = ?";
        try{
            return dbManager.executeQuery(sql, rs -> {
                if (!rs.next()) return null;

                User user = new User();
                user.setId(rs.getInt("id"));
                user.setFirstName(rs.getString("first_name"));
                user.setLastName(rs.getString("last_name"));
                user.setEmail(rs.getString("email"));
                return user;
            }, userId);
        } catch (SQLException e) {
            System.err.println("x User load failed: " + e.getMessage());
            return null;
        }

    }

    private void markToken(ApplicationToken appToken, String status, int adminId) throws SQLException {
        String sql = "UPDATE application_tokens SET status = ?, actioned_at = CURRENT_TIMESTAMP, " + "actioned_by = ? WHERE id = ?";
        dbManager.executeUpdate(sql, status, adminId, appToken.getId());
    }

    private String generateUserName(String firstName, String lastName) throws SQLException {
        String base = (firstName.trim().charAt(0) + lastName.trim().toLowerCase().replaceAll("[^a-z0-9]", ""));
        String candidate = base;
        int suffix = 1;
        while (isUserNameTaken(candidate)) {
            candidate = base + "-" + suffix++;
        }

        return candidate;
    }

    private boolean isUserNameTaken(String userName) throws SQLException {
        return dbManager.executeQuery(
                "SELECT COUNT(*) FROM users WHERE username = ?",
                rs -> {
                    rs.next();
                    return rs.getInt(1) > 0;
                }, userName);
    }

//    GENERATES THE FORMAT OF THE STUDENT NUMBER ie -> "2026--0000" DONT LIKE IT, THIS IS WHERE YOU CHANGE IT
    private String generateStudentId() throws SQLException {
        String year = String.valueOf(Year.now().getValue());

//        Finds the highest sequential number already used this year
        String sql = "SELECT MAX(CAST(SUBSTRING(student_id, 6) AS INT))" + "FROM students WHERE student_id LIKE ?";
        Integer maxSeq = dbManager.executeQuery(sql, rs -> {
            rs.next();
            return rs.getObject(1) == null ? 0 : rs.getInt(1);
        }, year + "-%");

        int next = maxSeq + 1;
        return String.format("%s-%04d", year, next);
    }

    private String generateCompliantPassword() {
        String upCase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lowCase = "abcdefghijklmnopqrstuvwxyz";
        String digits = "0123456789";
        String specials = "!@#$%^&*";

        SecureRandom rng = new SecureRandom();
        StringBuilder pw = new StringBuilder();
        pw.append(upCase.charAt(rng.nextInt(upCase.length())));
        pw.append(specials.charAt(rng.nextInt(upCase.length())));
        pw.append(digits.charAt(rng.nextInt(upCase.length())));
        pw.append(digits.charAt(rng.nextInt(upCase.length())));

        for (int i = 4; i < 10; i++) {
            pw.append(lowCase.charAt(rng.nextInt(lowCase.length())));
        }

        try {
            ValidationResult check = PasswordPolicyValidator.validateAgainstPolicy(pw.toString());

            if (!check.isValid()) return "Temp@1234";
        } catch (SQLException e) {
           return "Temp@1234";
        }

        return pw.toString();
    }

    public static class PendingApplication {
        private final int userId;
        private final String firstName;
        private final String lastName;
        private final String email;
        private final String token;
        private final LocalDateTime appliedAt;
        private final LocalDateTime expiresAt;

        public PendingApplication(int userId, String firstName, String lastName, String email, String token, LocalDateTime appliedAt, LocalDateTime expiresAt) {
            this.userId = userId;
            this.firstName = firstName;
            this.lastName = lastName;
            this.email = email;
            this.token = token;
            this.appliedAt = appliedAt;
            this.expiresAt = expiresAt;
        }

        public int getUserId() {
            return userId;
        }

        public String getFirstName() {
            return firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public String getEmail() {
            return email;
        }

        public String getToken() {
            return token;
        }

        public LocalDateTime getAppliedAt() {
            return appliedAt;
        }

        public LocalDateTime getExpiresAt() {
            return expiresAt;
        }

        public String fullName() {
            return firstName + " " + lastName;
        }

    }

    public static class ApprovalResult {
        private final boolean success;
        private final String email;
        private final String firstName;
        private final String username;
        private final String studentId;
        private final String tempPassword;
        private final String errorMessage;

        private ApprovalResult(String email, String firstName, String username, String studentId, String tempPassword){
            this.success = true;
            this.email = email;
            this.firstName = firstName;
            this.username = username;
            this.studentId = studentId;
            this.tempPassword = tempPassword;
            this.errorMessage = null;
        }

        private ApprovalResult(String errorMessage, boolean unused){
            this.success = false;
            this.email = null;
            this.firstName = null;
            this.username = null;
            this.studentId = null;
            this.tempPassword = null;
            this.errorMessage = errorMessage;
        }

        static ApprovalResult success(String email, String firstName, String username, String studentId, String tempPw) {
           return new ApprovalResult(email, firstName, username, studentId, tempPw);
        }

        static ApprovalResult fail(String msg){
            return new ApprovalResult(msg, false);
        }

        public boolean isSuccess() {return success;}
        public String getEmail() {return email;}
        public String getFirstName() { return firstName; }
        public String getUsername() { return username; }
        public String getStudentId() { return studentId; }
        public String getTempPassword() { return tempPassword; }
        public String getErrorMessage() { return errorMessage; }
    }
}
