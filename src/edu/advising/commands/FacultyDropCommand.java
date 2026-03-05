package edu.advising.commands;

// ============================================================================
// WEEK 5: COMMAND PATTERN - FacultyDropCommand (Concrete Command)
// ============================================================================
//
// FEATURE:  Faculty Information → Faculty Drop/Census Roster
//           (Faculty administratively drops a student from their section)
//
// WHY THIS IS A SEPARATE COMMAND FROM DropCommand:
//   A student dropping themselves (DropCommand) and a faculty member
//   administratively dropping a student are conceptually different:
//     1. AUTHORIZATION: Faculty drops need a different permission check
//        (faculty must own the section). Week 7 Decorator will wrap this.
//     2. REASON CODE: Faculty drops require a documented reason
//        (no-show, census, academic) logged in the enrollment record.
//     3. NOTIFICATION: The student must be notified that they were dropped
//        by faculty — this is a different notification type and message.
//     4. AUDIT: Faculty drops are surfaced in admin reports separately from
//        student self-drops. The command_type "FACULTY_DROP" makes queries easy.
//     5. UNDO POLICY: Faculty may want to reinstate a student within the
//        census period — undo re-enrolls the student.
//
// GUI INTEGRATION:
//   // On faculty class roster → right-click → "Administrative Drop":
//   Student selectedStudent = rosterTable.getSelectedStudent();
//   String reason = reasonDialog.getSelectedReason(); // "NO_SHOW", "CENSUS", etc.
//   FacultyDropCommand cmd = new FacultyDropCommand(faculty, student, section, reason);
//   executor.execute(cmd);
//
//   if (cmd.wasSuccessful()) {
//       showConfirmation("Student dropped. They have been notified.");
//       rosterTable.removeStudent(selectedStudent);
//   }
//
// ============================================================================

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.advising.core.DatabaseManager;
import edu.advising.notifications.NotificationManager;
import edu.advising.notifications.ObservableStudent;
import edu.advising.users.Faculty;
import edu.advising.users.Student;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class FacultyDropCommand extends BaseCommand {

    // ── State needed for execute and undo ────────────────────────────────────

    private final Faculty faculty;
    private ObservableStudent student;
    private Section section;
    private final String dropReason;  // NO_SHOW, CENSUS, ACADEMIC_INTEGRITY, OTHER

    // Captured during execute() for use in undo() and serialization
    private int droppedEnrollmentId;

    private final NotificationManager notificationManager;
    private final DatabaseManager     dbManager;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param faculty    The faculty member performing the drop.
     * @param student    The student being dropped.
     * @param section    The course section they are being dropped from.
     * @param dropReason Documented reason for the administrative drop.
     */
    public FacultyDropCommand(Faculty faculty, ObservableStudent student,
                              Section section, String dropReason) {
        super();
        this.commandType         = "FACULTY_DROP";
        this.faculty             = faculty;
        this.student             = student;
        this.section             = section;
        this.dropReason          = (dropReason != null) ? dropReason : "UNSPECIFIED";
        this.notificationManager = NotificationManager.getInstance();
        this.dbManager           = DatabaseManager.getInstance();
    }

    // -------------------------------------------------------------------------
    // Command Interface — execute()
    // -------------------------------------------------------------------------

    @Override
    public void execute() {
        executionTime = LocalDateTime.now();

        // ── Authorization check: faculty must own this section ───────────────
        // NOTE: In Week 7 (Decorator Pattern), this check will be handled by
        // FacultyPermissions.canDrop(section). For now, we check inline.
        if (section.getFacultyId() != faculty.getId()) {
            successful   = false;
            errorMessage = String.format("Faculty %s does not own section %s.",
                    faculty.getFullName(), section.getCourseCode());
            System.out.println("✗ " + errorMessage);
            return;
        }

        // ── Capture the enrollment ID before dropping, for undo purposes ─────
        try {
            Optional<Enrollment> enrollment = section.getEnrollments().stream()
                    .filter(e -> e.getStudentId() == student.getId()
                            && "ENROLLED".equals(e.getStatus()))
                    .findFirst();

            if (enrollment.isEmpty()) {
                successful   = false;
                errorMessage = String.format("Student %s is not enrolled in %s.",
                        student.getStudentId(), section.getCourseCode());
                System.out.println("✗ " + errorMessage);
                return;
            }

            droppedEnrollmentId = enrollment.get().getId();

            // ── Update enrollment record with drop details ───────────────────
            Enrollment e = enrollment.get();
            e.setStatus("DROPPED");
            e.setDroppedAt(LocalDateTime.now());
            e.setDropReason(dropReason + " (Faculty: " + faculty.getFullName() + ")");
            dbManager.upsert(e);

            // ── Update section's enrolled count ──────────────────────────────
            // section.drop() handles the in-memory list and upserts the section.
            section.drop(student);

            executed   = true;
            successful = true;

            System.out.printf("✓ Faculty drop: %s dropped %s from %s (Reason: %s)%n",
                    faculty.getFullName(), student.getFullName(),
                    section.getCourseCode(), dropReason);

            // ── Notify the student they were administratively dropped ─────────
            // This is a high-priority notification — student needs to know ASAP.
            notificationManager.notifyRegistration(student, section.getCourseCode(), false);
            // TODO Week 4 enhancement: add a faculty-drop-specific notification type
            //   that includes the reason code, so the student can respond if needed.

            // ── Check if anyone on the waitlist should be promoted ────────────
            promoteFromWaitlistIfAvailable();

        } catch (SQLException | IllegalAccessException e) {
            successful   = false;
            errorMessage = "Faculty drop failed: " + e.getMessage();
            System.err.println("✗ " + errorMessage);
        }
    }

    // -------------------------------------------------------------------------
    // Command Interface — undo()
    // -------------------------------------------------------------------------

    @Override
    public void undo() {
        if (!executed || !successful) {
            System.out.println("Cannot undo — drop was not completed.");
            return;
        }

        // Re-enroll the student in the section (reverses the drop).
        if (section.hasCapacity()) {
            int newEnrollmentId = section.enroll(student);
            if (newEnrollmentId > 0) {
                undoneAt = LocalDateTime.now();
                isUndone = true;
                System.out.printf("↶ Undone: %s re-enrolled in %s%n",
                        student.getFullName(), section.getCourseCode());
                notificationManager.notifyRegistration(student, section.getCourseCode(), true);
            } else {
                System.out.println("✗ Undo failed — could not re-enroll student.");
            }
        } else {
            System.out.printf("✗ Cannot undo — %s is now full.%n", section.getCourseCode());
        }
    }

    @Override
    public boolean isUndoable() {
        // Can only reinstate if the section still has capacity.
        return executed && successful && section.hasCapacity();
    }

    @Override
    public String getDescription() {
        return String.format("Faculty drop: %s from %s (Reason: %s)",
                student.getFullName(), section.getCourseCode(), dropReason);
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    @Override
    protected String serializeCommandData() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = new HashMap<>();
        data.put("facultyId",           faculty.getId());
        data.put("studentPk",           student.getId());
        data.put("sectionId",           section.getId());
        data.put("dropReason",          dropReason);
        data.put("droppedEnrollmentId", droppedEnrollmentId);
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("FacultyDropCommand: serialization failed", e);
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
            this.section             = dbManager.fetchOne(Section.class, "id", sectionId);
            this.droppedEnrollmentId = (int) data.get("droppedEnrollmentId");

        } catch (JsonProcessingException | SQLException e) {
            throw new RuntimeException("FacultyDropCommand: deserialization failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private Helper
    // -------------------------------------------------------------------------

    /**
     * After a drop frees a seat, promote the next eligible waitlist student.
     * Mirrors the logic in DropCommand.promoteFromWaitlist() but also notifies
     * the promoted student via the Observer system.
     */
    private void promoteFromWaitlistIfAvailable() {
        try {
            if (!section.getWaitlist().isEmpty() && section.hasCapacity()) {
                WaitlistEntry next = section.getWaitlist().get(0);
                Student waitlisted = next.getStudent();
                section.removeFromWaitlist(waitlisted);
                int newEnrollmentId = section.enroll(waitlisted);
                if (newEnrollmentId > 0) {
                    System.out.printf("↑ %s promoted from waitlist into %s%n",
                            waitlisted.getFullName(), section.getCourseCode());
                    // Notify the promoted student via the Observer chain.
                    // TODO: wrap waitlisted student in ObservableStudent before notifying
                    notificationManager.notifyRegistration(
                            ObservableStudent.fromSuperType((Student) waitlisted),
                            section.getCourseCode(), true);
                }
            }
        } catch (SQLException e) {
            System.err.println("FacultyDropCommand: waitlist promotion failed — " + e.getMessage());
        }
    }
}