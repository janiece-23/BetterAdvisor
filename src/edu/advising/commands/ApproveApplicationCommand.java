package edu.advising.commands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.advising.auth.ApplicationAdminService;
import edu.advising.core.DatabaseManager;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class ApproveApplicationCommand extends BaseCommand{
    private final String token;
    private final int adminId;

    //capture during execute() -- Needed for undue
    private String savedEmail;
    private String savedUserName;
    private String savedStudentId;
    private int savedUserId;

    private final ApplicationAdminService adminService;
    private final DatabaseManager dbManager;

    private ApproveApplicationCommand(String token, int adminId){
        super();
        this.commandType = "APPROVED APPLICATION";
        this.token = token;
        this.adminId = adminId;
        this.adminService = new ApplicationAdminService();
        this.dbManager = DatabaseManager.getInstance();
    }

    @Override
    protected String serializeCommandData() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("adminId", adminId);
        data.put("savedEmail", savedEmail);
        data.put("savedUserName", savedUserName);
        data.put("savedStudentId", savedStudentId);
        data.put("savedUserId", savedUserId);

        try{
            return mapper.writeValueAsString(data);
        }catch(JsonProcessingException e) {
            throw new RuntimeException("ApproveApplicationCommand: serialized failed", e);
        }
    }

    @Override
    protected void deserializeCommandData(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try{
            Map data = mapper.readValue(json, Map.class);
            this.savedEmail = (String) data.get("savedEmail");
            this.savedUserName = (String) data.get("savedUserName");
            this.savedStudentId = (String) data.get("savedStudentId");
            this.savedUserId = (int) data.get("savedUserId");

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void execute() {
        executionTime = LocalDateTime.now();

        ApplicationAdminService.ApprovalResult result = adminService.approves(token, adminId);

        if (result.isSuccess()) {
            //captures state for undo
            savedEmail = result.getEmail();
            savedUserName = result.getUsername();
            savedStudentId = result.getStudentId();
            savedUserId = lookupUserIdByEmail(savedEmail);

            executed = true;
            successful = true;

            System.out.printf(":) ApprovedApplicationCommand executed | username: %s | studentId: %s%n", savedUserName, savedStudentId);
            //TODO: pass result.getTempPassword() and result.getEmail()
            // to email layer to send credentials to the student
        } else {
            successful = false;
            errorMessage = result.getErrorMessage();
        }

    }

    @Override
    public void undo() {
        if (!executed || !successful) {
            System.out.println("Cannot undo - approval was not completed");
            return;
        }

        try{
            // 1. Deactivate user and reset placeholder credentials
            String userReset = "UPDATE users SET is_active = FALSE, " + "username = ?, password = 'LOCKED', " + "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            dbManager.executeUpdate(userReset, "pending" + savedEmail, savedUserId);

            //2. Delete the student row
            dbManager.executeUpdate("DELTE FROM students WHERE id = ?", savedUserId);

            //3. Reset token back to PENDING
            String resetToken = "UPDATE Application_tokens SET status = 'PENDING', " + "actioned_at = NULL, actioned_by = NULL WHERE token = ?";
            dbManager.executeUpdate(resetToken, token);

            undoneAt = LocalDateTime.now();
            isUndone = true;

            System.out.printf("Undone approval reversed for %s - BACK TO PENDING%n", savedEmail);

        } catch(SQLException e){
            System.err.println("X Undo failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isUndoable() {
        return executed && successful;
    }

    @Override
    public String getDescription() {
        return String.format("Approved application | token: %s | adminId: %d", token, adminId);
    }

    private int lookupUserIdByEmail(String email){
        try{
            return dbManager.executeQuery(
                    "SELECT id FROM useres WHERE email = ?",
                    rs -> rs.next() ? rs.getInt("id") : -1
            );
        } catch (SQLException e) {
            System.err.println("ApproveApplicationCommand: user lookup failed - " + e.getMessage());
            return -1;
        }
    }
}
