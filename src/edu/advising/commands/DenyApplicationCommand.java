package edu.advising.commands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.advising.auth.ApplicationAdminService;
import edu.advising.core.DatabaseManager;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * DenyApplicationCommand - Denies a pending student application.
 *
 * EXECUTE:
 *   - Delegates to AdminApplicationService.deny()
 *   - Marks the token DENIED
 *   - User row stays (audit trail), is_active remains FALSE
 *
 * UNDO:
 *   - Resets token status back to PENDING
 *   - No user row changes needed (it was never activated)
 */
public class DenyApplicationCommand extends BaseCommand {

    private final String token;
    private final int    adminId;

    private final ApplicationAdminService adminService;
    private final DatabaseManager         dbManager;

    public DenyApplicationCommand(String token, int adminId) {
        super();
        this.commandType  = "DENY_APPLICATION";
        this.token        = token;
        this.adminId      = adminId;
        this.adminService = new ApplicationAdminService();
        this.dbManager    = DatabaseManager.getInstance();
    }

    @Override
    public void execute() {
        executionTime = LocalDateTime.now();

        boolean success = adminService.deny(token, adminId);

        if (success) {
            executed   = true;
            successful = true;
            System.out.printf(" :) DenyApplicationCommand executed | token: %s%n", token);
        } else {
            successful   = false;
            errorMessage = "Denial failed — token may not exist or is already actioned.";
            System.out.println("✗ " + errorMessage);
        }
    }

    @Override
    public void undo() {
        if (!executed || !successful) {
            System.out.println("Cannot undo — denial was not completed.");
            return;
        }

        try {
            // Reset token back to PENDING — user row doesn't need touching
            // since is_active was already FALSE and no students row was created
            String resetToken =
                    "UPDATE application_tokens SET status = 'PENDING', " + "actioned_at = NULL, actioned_by = NULL WHERE token = ?";
            dbManager.executeUpdate(resetToken, token);

            undoneAt = LocalDateTime.now();
            isUndone = true;

            System.out.printf("↶ Undone: Denial reversed for token=%s — back to PENDING%n", token);

        } catch (SQLException e) {
            System.err.println("X Undo failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isUndoable() {
        return executed && successful;
    }

    @Override
    public String getDescription() {
        return String.format("Deny application | token: %s | adminId: %d", token, adminId);
    }

    @Override
    protected String serializeCommandData() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = new HashMap<>();
        data.put("token",   token);
        data.put("adminId", adminId);

        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("DenyApplicationCommand: serialization failed", e);
        }
    }

    @Override
    protected void deserializeCommandData(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            Map data = mapper.readValue(json, Map.class);
            // token and adminId are final — deserialization is for audit display only
            System.out.println("DenyApplicationCommand: deserialized for audit | token=" +
                    data.get("token"));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("DenyApplicationCommand: deserialization failed", e);
        }
    }
}