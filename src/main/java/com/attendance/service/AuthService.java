package com.attendance.service;

import com.attendance.dao.FacultyDAO;
import com.attendance.dao.StudentDAO;
import com.attendance.dao.UserDAO;
import com.attendance.exception.AuthenticationException;
import com.attendance.model.Faculty;
import com.attendance.model.Role;
import com.attendance.model.Student;
import com.attendance.model.User;
import com.attendance.util.PasswordUtil;

/**
 * Handles login for all three roles. On success, also resolves the
 * role-specific profile (Student or Faculty row) so the UI has everything
 * it needs in one call. Admin has no extra profile beyond the User row.
 */
public class AuthService {

    private final UserDAO userDAO = new UserDAO();
    private final StudentDAO studentDAO = new StudentDAO();
    private final FacultyDAO facultyDAO = new FacultyDAO();

    public static class LoginResult {
        public final User user;
        public final Student studentProfile; // non-null only for STUDENT
        public final Faculty facultyProfile;  // non-null only for FACULTY

        LoginResult(User user, Student studentProfile, Faculty facultyProfile) {
            this.user = user;
            this.studentProfile = studentProfile;
            this.facultyProfile = facultyProfile;
        }
    }

    /**
     * @param expectedRole the role selected on the login screen; if it doesn't match the
     *                     account's actual role, login is rejected even if the password is right -
     *                     this is what keeps a student from ever landing on the faculty dashboard.
     */
    public LoginResult login(String username, String plainPassword, Role expectedRole) throws AuthenticationException {
        if (username == null || username.isBlank() || plainPassword == null || plainPassword.isBlank()) {
            throw new AuthenticationException("Enter both a username/ID and a password.");
        }

        User user = userDAO.findByUsername(username.trim())
                .orElseThrow(() -> new AuthenticationException("No account found for '" + username + "'."));

        if (user.getRole() != expectedRole) {
            throw new AuthenticationException("This ID is not registered as a " + expectedRole.name().toLowerCase() + ".");
        }

        if (!PasswordUtil.matches(plainPassword, user.getPasswordHash())) {
            throw new AuthenticationException("Incorrect password.");
        }

        // Old unsalted SHA-256 hashes are upgraded to salted PBKDF2 the first time the user logs in.
        if (PasswordUtil.needsUpgrade(user.getPasswordHash())) {
            userDAO.updatePasswordHash(user.getId(), PasswordUtil.hash(plainPassword));
        }

        return switch (user.getRole()) {
            case STUDENT -> {
                Student student = studentDAO.findByUserId(user.getId())
                        .orElseThrow(() -> new AuthenticationException("Student profile is missing for this account."));
                yield new LoginResult(user, student, null);
            }
            case FACULTY -> {
                Faculty faculty = facultyDAO.findByUserId(user.getId())
                        .orElseThrow(() -> new AuthenticationException("Faculty profile is missing for this account."));
                yield new LoginResult(user, null, faculty);
            }
            case ADMIN -> new LoginResult(user, null, null);
        };
    }
}
