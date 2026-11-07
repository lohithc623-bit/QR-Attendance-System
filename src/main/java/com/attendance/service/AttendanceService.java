package com.attendance.service;

import com.attendance.dao.AttendanceDAO;
import com.attendance.dao.ClassDAO;
import com.attendance.dao.EnrollmentDAO;
import com.attendance.dao.StudentDAO;
import com.attendance.dao.SubjectDAO;
import com.attendance.exception.AccessDeniedException;
import com.attendance.exception.DuplicateAttendanceException;
import com.attendance.exception.ExpiredQRCodeException;
import com.attendance.exception.InvalidQRCodeException;
import com.attendance.exception.StudentClassMismatchException;
import com.attendance.exception.StudentNotRegisteredException;
import com.attendance.model.AttendanceRecord;
import com.attendance.model.AttendanceStatus;
import com.attendance.model.RosterEntry;
import com.attendance.model.SchoolClass;
import com.attendance.model.Student;
import com.attendance.model.StudentAttendanceSummary;
import com.attendance.model.Subject;
import com.attendance.model.SubjectAttendanceSummary;
import com.attendance.util.QRTokenService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * All attendance business rules. A scanned QR becomes a stored row only after, in order:
 *   0. the faculty member really teaches this subject in this class       -> AccessDeniedException
 *   1. the payload is a well-formed, correctly signed rotating QR         -> InvalidQRCodeException
 *   1b. ...and is not older than the rotation window                      -> ExpiredQRCodeException
 *   2. the student is in the selected class                               -> StudentClassMismatchException
 *   3. the student is registered for the selected subject                 -> StudentNotRegisteredException
 *   4. the student is not already PRESENT for this subject/date/period    -> DuplicateAttendanceException
 */
public class AttendanceService {

    public static final int MAX_PERIOD = 8;

    private final StudentDAO studentDAO = new StudentDAO();
    private final SubjectDAO subjectDAO = new SubjectDAO();
    private final ClassDAO classDAO = new ClassDAO();
    private final EnrollmentDAO enrollmentDAO = new EnrollmentDAO();
    private final AttendanceDAO attendanceDAO = new AttendanceDAO();

    // --- Faculty: scanning -----------------------------------------------------------------

    public Student markAttendance(String scannedPayload, int facultyId, int classId, int subjectId,
                                  LocalDate date, int period)
            throws InvalidQRCodeException, StudentClassMismatchException,
                   StudentNotRegisteredException, DuplicateAttendanceException {

        requireOwnSubject(facultyId, classId, subjectId);
        validateSession(date, period);

        if (scannedPayload == null || scannedPayload.isBlank()) {
            throw new InvalidQRCodeException("Could not read a QR code.");
        }
        QRTokenService.Parsed parsed = QRTokenService.parse(scannedPayload);
        if (parsed == null) {
            throw new InvalidQRCodeException("That is not an attendance QR code (or it is an old, static one).");
        }
        Student student = studentDAO.findById(parsed.studentId)
                .orElseThrow(() -> new InvalidQRCodeException("This QR code does not match any registered student."));

        switch (QRTokenService.verify(parsed, student.getQrToken(), Instant.now())) {
            case INVALID -> throw new InvalidQRCodeException("This QR code is not valid.");
            case EXPIRED -> throw new ExpiredQRCodeException(
                    "This QR code has expired - ask the student to show the live code on their screen.");
            case VALID -> { }
        }

        String label = student.getFullName() + " (" + student.getRegisterNumber() + ")";
        if (student.getClassId() != classId) {
            throw new StudentClassMismatchException(label + " does not belong to the selected class.");
        }
        if (!enrollmentDAO.isEnrolled(student.getId(), subjectId)) {
            throw new StudentNotRegisteredException(label + " is not registered for this subject.");
        }
        if (AttendanceStatus.PRESENT.equals(
                attendanceDAO.findStatus(student.getId(), subjectId, date, period).orElse(null))) {
            throw new DuplicateAttendanceException(label + " is already marked present for this period.");
        }

        // An existing ABSENT row (session was finalized earlier) is upgraded to PRESENT.
        attendanceDAO.upsert(student.getId(), facultyId, subjectId, classId, date, period,
                AttendanceStatus.PRESENT, "QR", LocalDateTime.now());
        return student;
    }

    // --- Faculty: manual override, absent marking, live roster -----------------------------

    /** Manually set a student PRESENT or ABSENT for a session (no QR needed). */
    public void setStatus(int facultyId, int studentId, int classId, int subjectId,
                          LocalDate date, int period, String status)
            throws StudentClassMismatchException, StudentNotRegisteredException {
        requireOwnSubject(facultyId, classId, subjectId);
        validateSession(date, period);
        if (!AttendanceStatus.isValid(status)) {
            throw new IllegalArgumentException("Status must be PRESENT or ABSENT.");
        }
        Student student = studentDAO.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown student."));
        if (student.getClassId() != classId) {
            throw new StudentClassMismatchException(student.getFullName() + " does not belong to the selected class.");
        }
        if (!enrollmentDAO.isEnrolled(studentId, subjectId)) {
            throw new StudentNotRegisteredException(student.getFullName() + " is not registered for this subject.");
        }
        attendanceDAO.upsert(studentId, facultyId, subjectId, classId, date, period, status, "MANUAL", LocalDateTime.now());
    }

    /** Close the session: everyone enrolled who has not been scanned/marked becomes ABSENT. @return how many. */
    public int finalizeSession(int facultyId, int classId, int subjectId, LocalDate date, int period) {
        requireOwnSubject(facultyId, classId, subjectId);
        validateSession(date, period);
        return attendanceDAO.markRemainingAbsent(facultyId, subjectId, classId, date, period, LocalDateTime.now());
    }

    /** Live view: every enrolled student with PRESENT / ABSENT / not marked yet. */
    public List<RosterEntry> getRoster(int facultyId, int classId, int subjectId, LocalDate date, int period) {
        requireOwnSubject(facultyId, classId, subjectId);
        validateSession(date, period);
        return attendanceDAO.findRoster(classId, subjectId, date, period);
    }

    public List<AttendanceRecord> getSessionAttendance(int classId, int subjectId, LocalDate date, int period) {
        return attendanceDAO.findForSession(classId, subjectId, date, period);
    }

    public List<SchoolClass> getClassesForFaculty(int facultyId) {
        return classDAO.findByFaculty(facultyId);
    }

    public List<Subject> getSubjectsForFaculty(int facultyId, int classId) {
        return subjectDAO.findByClassAndFaculty(classId, facultyId);
    }

    // --- Student views ------------------------------------------------------------------------

    public List<AttendanceRecord> getAttendanceHistory(int studentId) {
        return attendanceDAO.findByStudentId(studentId);
    }

    public List<AttendanceRecord> getAttendanceForDate(int studentId, LocalDate date) {
        return attendanceDAO.findByStudentIdAndDate(studentId, date);
    }

    /** One SQL query, however many subjects the student has. */
    public List<SubjectAttendanceSummary> getSubjectWiseSummary(int studentId) {
        return attendanceDAO.summariseStudentSubjects(studentId);
    }

    public static double overallPercentage(List<SubjectAttendanceSummary> summaries) {
        int present = 0;
        int total = 0;
        for (SubjectAttendanceSummary s : summaries) {
            present += s.getPresentCount();
            total += s.getTotalClasses();
        }
        return total == 0 ? 0.0 : (present * 100.0) / total;
    }

    public double getOverallPercentage(int studentId) {
        return overallPercentage(getSubjectWiseSummary(studentId));
    }

    // --- Faculty reports -----------------------------------------------------------------------

    public List<StudentAttendanceSummary> getSubjectWiseReport(int facultyId, int subjectId, int classId) {
        requireOwnSubject(facultyId, classId, subjectId);
        return attendanceDAO.summariseSubject(subjectId, classId);
    }

    public List<StudentAttendanceSummary> getClassWiseReport(int facultyId, int classId) {
        if (subjectDAO.findByClassAndFaculty(classId, facultyId).isEmpty()) {
            throw new AccessDeniedException("You do not teach any subject in this class.");
        }
        return attendanceDAO.summariseClass(classId);
    }

    // --- Guards ----------------------------------------------------------------------------------

    private Subject requireOwnSubject(int facultyId, int classId, int subjectId) {
        Subject subject = subjectDAO.findById(subjectId)
                .orElseThrow(() -> new AccessDeniedException("Unknown subject."));
        if (subject.getClassId() != classId || subject.getFacultyId() != facultyId) {
            throw new AccessDeniedException("You are not the faculty for this subject in this class.");
        }
        return subject;
    }

    private void validateSession(LocalDate date, int period) {
        if (date == null) {
            throw new IllegalArgumentException("Date is required.");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Attendance cannot be recorded for a future date.");
        }
        if (period < 1 || period > MAX_PERIOD) {
            throw new IllegalArgumentException("Period must be between 1 and " + MAX_PERIOD + ".");
        }
    }
}
