package edu.advising.commands;

// ============================================================================
// WEEK 5: COMMAND PATTERN - GrantWaitlistPermissionCommand (Concrete Command)
// ============================================================================
//
// FEATURE:  Faculty Information → Permission to Add Waitlisted Students
//           (Faculty explicitly grants a waitlisted student permission to enroll
//            in a section that is at or over capacity)
//
// REAL-WORLD CONTEXT (WebAdvisor):
//   Some sections have "permission-required" flags or a faculty override flow.
//   A student on the waitlist can see they are position #1 and contact the
//   instructor. The instructor reviews their situation and grants permission.
//   That permission shows up for the student as a one-time-use enrollment token.
//
// WHY COMMAND PATTERN HERE:
//   1. REVERSIBLE: Revoking a permission grant before the student acts on it
//      should be an undo, not a separate "revoke" flow.
//   2. LOGGED: Faculty overrides must be audited ("Prof. Smith over-enrolled CS101").
//   3. TIME-BOUNDED: The permission expires if unused — the command record
//      stores the expiry and can be queried by the registration flow.
//   4. FUTURE PIPELINE HOOK: Week 14's registration pipeline will check for
//      a valid PermissionGrant before allowing enrollment in a full section.
//
// GUI INTEGRATION:
//   // Faculty roster → select waitlisted student → "Grant Permission" button:
//   GrantWaitlistPermissionCommand cmd =
//       new GrantWaitlistPermissionCommand(faculty, student, section, "Student has prerequisite waiver");
//   executor.execute(cmd);
//
//   if (cmd.wasSuccessful()) {
//       showInfo(student.getFullName() + " can now enroll within 48 hours.");
//   }
//
//   // Student portal checks permissions when attempting to register:
//   boolean canOverride = PermissionGrant.hasActiveGrant(student.getId(), section.getId());
//
// ============================================================================

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.advising.core.Column;
import edu.advising.core.DatabaseManager;
import edu.advising.core.Id;
import edu.advising.core.Table;
import edu.advising.notifications.NotificationManager;
import edu.advising.notifications.ObservableStudent;
import edu.advising.users.Faculty;
import edu.advising.users.Student;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class GrantWaitlistPermissionCommand extends BaseCommand {

    // ── State ────────────────────────────────────────────────────────────────

    private final Faculty faculty;
    private ObservableStudent student;
    private Section section;
    private final String notes;         // Optional faculty note ("prerequisite waived")
    private final int validForHours;    // How long the permission is active

    // Populated during execute() — needed for undo and student-facing display
    private int grantId;                // PK of the permission_grants row

    private final NotificationManager notificationManager;
    private final DatabaseManager     dbManager;

    // Default permission window: 48 hours before it expires unused
    private static final int DEFAULT_VALID_HOURS = 48;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public GrantWaitlistPermissionCommand(Faculty faculty, ObservableStudent student,
                                          Section section, String notes) {
        this(faculty, student, section, notes, DEFAULT_VALID_HOURS);
    }

    public GrantWaitlistPermissionCommand(Faculty faculty, ObservableStudent student,
                                          Section section, String notes, int validForHours) {
        super();
        this.commandType      = "GRANT_WAITLIST_PERMISSION";
        this.faculty          = faculty;
        this.student          = student;
        this.section          = section;
        this.notes            = notes;
        this.validForHours    = validForHours;
        this.notificationManager = NotificationManager.getInstance();
        this.dbManager           = DatabaseManager.getInstance();
    }

    // -------------------------------------------------------------------------
    // Command Interface — execute()
    // -------------------------------------------------------------------------

    @Override
    public void execute() {
        executionTime = LocalDateTime.now();

        // ── Verify faculty owns the section ──────────────────────────────────
        if (section.getFacultyId() != faculty.getId()) {
            successful   = false;
            errorMessage = "Only the section instructor can grant enrollment permission.";
            System.out.println("✗ " + errorMessage);
            return;
        }

        // ── Check student is actually on the waitlist ─────────────────────────
        try {
            boolean onWaitlist = section.getWaitlist().stream()
                    .anyMatch(we -> we.getStudentId() == student.getId());
            if (!onWaitlist) {
                successful   = false;
                errorMessage = String.format("%s is not on the waitlist for %s.",
                        student.getFullName(), section.getCourseCode());
                System.out.println("✗ " + errorMessage);
                return;
            }
        } catch (SQLException e) {
            successful   = false;
            errorMessage = "Could not verify waitlist status: " + e.getMessage();
            System.err.println("✗ " + errorMessage);
            return;
        }

        // ── Persist a permission_grants record ───────────────────────────────
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(validForHours);
        String sql = "INSERT INTO permission_grants " +
                "(faculty_id, student_id, section_id, granted_at, expires_at, notes, is_used, is_active) " +
                "VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, ?, FALSE, TRUE)";
        try {
            grantId = dbManager.executeInsert(sql,
                    faculty.getId(),
                    student.getId(),
                    section.getId(),
                    Timestamp.valueOf(expiresAt),
                    notes);

            if (grantId <= 0) {
                successful   = false;
                errorMessage = "Permission grant could not be saved.";
                return;
            }

            executed   = true;
            successful = true;

            System.out.printf("✓ Permission granted: %s may enroll in %s within %d hours.%n",
                    student.getFullName(), section.getCourseCode(), validForHours);

            // ── Notify the student that they may now register ─────────────────
            // This fires the Observer chain → email / push notification to student.
            notificationManager.notifyWaitlistUpdate(
                    student, section.getCourseCode(),
                    0); // Position 0 signals "you have a permission override"

        } catch (SQLException e) {
            successful   = false;
            errorMessage = "Failed to persist permission grant: " + e.getMessage();
            System.err.println("✗ " + errorMessage);
        }
    }

    // -------------------------------------------------------------------------
    // Command Interface — undo()
    // -------------------------------------------------------------------------

    @Override
    public void undo() {
        if (!executed || !successful || grantId <= 0) {
            System.out.println("Cannot undo — permission was not granted.");
            return;
        }

        // Deactivate the permission grant so the student can no longer use it.
        String sql = "UPDATE permission_grants SET is_active = FALSE WHERE id = ? AND is_used = FALSE";
        try {
            int updated = dbManager.executeUpdate(sql, grantId);
            if (updated > 0) {
                undoneAt = LocalDateTime.now();
                isUndone = true;
                System.out.printf("↶ Undone: Permission revoked for %s in %s.%n",
                        student.getFullName(), section.getCourseCode());
                // Notify student the permission was revoked.
                notificationManager.notifyWaitlistUpdate(
                        student, section.getCourseCode(),
                        section.getEnrolled()); // Show actual position again
            } else {
                System.out.println("✗ Permission could not be revoked — student may have already used it.");
            }
        } catch (SQLException e) {
            System.err.println("✗ Undo failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isUndoable() {
        // Can only revoke if the permission hasn't been used by the student yet.
        if (!executed || !successful || grantId <= 0) return false;
        try {
            return dbManager.executeQuery(
                    "SELECT is_used FROM permission_grants WHERE id = ?",
                    rs -> rs.next() && !rs.getBoolean("is_used"),
                    grantId);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public String getDescription() {
        return String.format("Grant waitlist permission: %s → %s (valid %dh)",
                student.getFullName(), section.getCourseCode(), validForHours);
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    @Override
    protected String serializeCommandData() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = new HashMap<>();
        data.put("facultyId",    faculty.getId());
        data.put("studentPk",    student.getId());
        data.put("sectionId",    section.getId());
        data.put("notes",        notes);
        data.put("validForHours",validForHours);
        data.put("grantId",      grantId);
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("GrantWaitlistPermissionCommand: serialization failed", e);
        }
    }

    @Override
    protected void deserializeCommandData(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            Map<String, Object> data = mapper.readValue(json, Map.class);
            int studentPk = (int) data.get("studentPk");
            int sectionId = (int) data.get("sectionId");

            Student raw = dbManager.fetchOne(Student.class, "id", studentPk);
            if (raw != null) this.student = ObservableStudent.fromSuperType(raw);
            this.section  = dbManager.fetchOne(Section.class, "id", sectionId);
            this.grantId  = (int) data.get("grantId");
        } catch (JsonProcessingException | SQLException e) {
            throw new RuntimeException("GrantWaitlistPermissionCommand: deserialization failed", e);
        }
    }
}