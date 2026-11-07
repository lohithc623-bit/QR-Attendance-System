package com.attendance.service;

import com.attendance.dao.ClassDAO;
import com.attendance.dao.EnrollmentDAO;
import com.attendance.dao.FacultyDAO;
import com.attendance.dao.StudentDAO;
import com.attendance.dao.SubjectDAO;
import com.attendance.dao.UserDAO;
import com.attendance.model.Faculty;
import com.attendance.model.Role;
import com.attendance.model.SchoolClass;
import com.attendance.model.Student;
import com.attendance.model.Subject;
import com.attendance.util.PasswordUtil;

import java.util.List;
import java.util.UUID;

/**
 * Everything the Admin dashboard needs: create/list/delete classes, subjects,
 * faculty and students. Creating a student or subject automatically keeps
 * subject_enrollments consistent, so the class/subject checks in
 * AttendanceService always see accurate data.
 */
public class AdminService {

    private final ClassDAO classDAO = new ClassDAO();
    private final SubjectDAO subjectDAO = new SubjectDAO();
    private final FacultyDAO facultyDAO = new FacultyDAO();
    private final StudentDAO studentDAO = new StudentDAO();
    private final UserDAO userDAO = new UserDAO();
    private final EnrollmentDAO enrollmentDAO = new EnrollmentDAO();

    // --- Classes ---
    public List<SchoolClass> listClasses() { return classDAO.findAll(); }

    public SchoolClass createClass(String className, String department, int semester, String section) {
        return classDAO.insert(className, department, semester, section);
    }

    public void deleteClass(int classId) { classDAO.delete(classId); }

    // --- Subjects ---
    public List<Subject> listSubjects() { return subjectDAO.findAll(); }

    /** Creating a subject immediately enrolls every current student of its class. */
    public Subject createSubject(String code, String name, int classId, int facultyId) {
        Subject subject = subjectDAO.insert(code, name, classId, facultyId);
        enrollmentDAO.enrollAllStudentsOfClass(classId, subject.getId());
        return subject;
    }

    public void deleteSubject(int subjectId) { subjectDAO.delete(subjectId); }

    // --- Faculty ---
    public List<Faculty> listFaculty() { return facultyDAO.findAll(); }

    public static final int MIN_PASSWORD_LENGTH = 8;

    private static void requireStrongEnough(String plainPassword) {
        if (plainPassword == null || plainPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    public Faculty createFaculty(String facultyCode, String fullName, String department, String plainPassword) {
        requireStrongEnough(plainPassword);
        int userId = userDAO.insert(facultyCode, PasswordUtil.hash(plainPassword), fullName, Role.FACULTY);
        return facultyDAO.insert(userId, facultyCode, department);
    }

    public void deleteFaculty(int facultyId) { facultyDAO.delete(facultyId); }

    // --- Students ---
    public List<Student> listStudents() { return studentDAO.findAll(); }

    /**
     * Creates the login, the student profile with a fresh random QR token,
     * and enrolls the student into every subject already offered to their class.
     */
    public Student createStudent(String registerNumber, String fullName, String department,
                                  int semester, String section, int classId, String plainPassword) {
        requireStrongEnough(plainPassword);
        int userId = userDAO.insert(registerNumber, PasswordUtil.hash(plainPassword), fullName, Role.STUDENT);
        String qrToken = UUID.randomUUID().toString();
        Student student = studentDAO.insert(userId, registerNumber, department, semester, section, classId, qrToken);
        enrollmentDAO.enrollStudentInAllSubjectsOfClass(student.getId(), classId);
        return student;
    }

    public void deleteStudent(int studentId) { studentDAO.delete(studentId); }
}
