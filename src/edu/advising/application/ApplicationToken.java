package edu.advising.application;

import edu.advising.core.Column;
import edu.advising.core.Id;
import edu.advising.core.Table;

import java.time.LocalDateTime;

/**
 * ApplicationToken
 *
 * Represents a pending student application. One row per applicant.
 * The token is emailed to admins as part of an approve/deny link, and also
 * displayed in the web-based admin queue — both paths use the same record.
 *
 * DB TABLE: application_tokens
 * Add to DatabaseManager.initializeDatabase():
 *
 *   executeUpdate("CREATE TABLE IF NOT EXISTS application_tokens (" +
 *       "id INT AUTO_INCREMENT PRIMARY KEY, " +
 *       "user_id INT NOT NULL, " +
 *       "token VARCHAR(255) UNIQUE NOT NULL, " +
 *       "status VARCHAR(20) DEFAULT 'PENDING', " +  // PENDING, APPROVED, DENIED
 *       "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
 *       "expires_at TIMESTAMP NOT NULL, " +
 *       "actioned_at TIMESTAMP, " +
 *       "actioned_by INT, " +                        // admin user id
 *       "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, " +
 *       "FOREIGN KEY (actioned_by) REFERENCES users(id))");
 */

@Table(name = "application_tokens")

public class ApplicationToken {

    @Id(isPrimary = true)
    @Column(name="id", upsertIgnore = true)
    private int id;

    @Id
    @Column(name="user_id", foreignKey = true)
    private int userId;

    @Column(name="token")
    private String token;

    //STATUS: PENDING, APPROVED, DENIED
    @Column(name="status")
    private String status;

    @Column(name="created_at")
    private LocalDateTime createdAt;

    @Column(name="expires_at")
    private LocalDateTime expiresAt;

    @Column(name="actioned_at")
    private LocalDateTime actionedAt;

    @Column(name="actioned_by", nullableforeignKey = true)
    //admin user id
    private int actionedBy;

    public ApplicationToken(int userId, String token, LocalDateTime expiresAt) {
       this.userId = userId;
       this.token = token;
       this.expiresAt = expiresAt;
       this.status = "PENDING";
       this.createdAt = LocalDateTime.now();
    }

    public boolean isPending(){return "PENDING".equals(status);}
    public boolean isExpired(){return LocalDateTime.now().isAfter(expiresAt);}
    public boolean isApproved(){return "APPROVED".equals(status);}
    public boolean isDenied(){return "DENIED".equals(status);}

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getActionedAt() {
        return actionedAt;
    }

    public void setActionedAt(LocalDateTime actionedAt) {
        this.actionedAt = actionedAt;
    }

    public int getActionedBy() {
        return actionedBy;
    }

    public void setActionedBy(int actionedBy) {
        this.actionedBy = actionedBy;
    }
}
