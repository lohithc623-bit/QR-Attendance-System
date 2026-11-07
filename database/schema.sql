-- QR Attendance System — MySQL schema
-- CSE203 OOP using Java, Mini Project

CREATE DATABASE IF NOT EXISTS qr_attendance;
USE qr_attendance;

-- Drop children before parents (students references classes & users,
-- faculty references users, so students/faculty must go before classes/users).
DROP TABLE IF EXISTS attendance;
DROP TABLE IF EXISTS subject_enrollments;
DROP TABLE IF EXISTS subjects;
DROP TABLE IF EXISTS students;
DROP TABLE IF EXISTS faculty;
DROP TABLE IF EXISTS classes;
DROP TABLE IF EXISTS users;

-- One row per login, of any role.
CREATE TABLE users (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,   -- register number (student) / faculty id (faculty) / admin username
    password_hash VARCHAR(255) NOT NULL,          -- salted PBKDF2-HMAC-SHA256 (pbkdf2$iters$salt$hash), never plain text
    full_name     VARCHAR(100) NOT NULL,
    role          ENUM('STUDENT', 'FACULTY', 'ADMIN') NOT NULL
) ENGINE=InnoDB;

-- A class group, e.g. "II CSE(AI&ML) A".
CREATE TABLE classes (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    class_name  VARCHAR(100) NOT NULL,
    department  VARCHAR(50)  NOT NULL,
    semester    INT          NOT NULL,
    section     VARCHAR(5)   NOT NULL,
    UNIQUE KEY uniq_class (department, semester, section)
) ENGINE=InnoDB;

CREATE TABLE students (
    id               INT AUTO_INCREMENT PRIMARY KEY,
    user_id          INT NOT NULL UNIQUE,
    register_number  VARCHAR(30) NOT NULL UNIQUE,
    department       VARCHAR(50) NOT NULL,
    semester         INT NOT NULL,
    section          VARCHAR(5) NOT NULL,
    class_id         INT NOT NULL,
    qr_token         VARCHAR(64) NOT NULL UNIQUE,   -- opaque random identifier, NOT the password
    FOREIGN KEY (user_id)  REFERENCES users(id)   ON DELETE CASCADE,
    FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE faculty (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    user_id      INT NOT NULL UNIQUE,
    faculty_code VARCHAR(30) NOT NULL UNIQUE,
    department   VARCHAR(50) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- A subject offered to one class, taught by one faculty member.
CREATE TABLE subjects (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    subject_code  VARCHAR(20)  NOT NULL,
    subject_name  VARCHAR(100) NOT NULL,
    class_id      INT NOT NULL,
    faculty_id    INT NOT NULL,
    UNIQUE KEY uniq_subject_per_class (subject_code, class_id),
    FOREIGN KEY (class_id)   REFERENCES classes(id)  ON DELETE CASCADE,
    FOREIGN KEY (faculty_id) REFERENCES faculty(id)  ON DELETE CASCADE
) ENGINE=InnoDB;

-- Which students are registered for which subject (kept separate from
-- class membership so "belongs to class" and "registered for subject"
-- are two genuinely distinct checks, as the spec asks for).
CREATE TABLE subject_enrollments (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    student_id  INT NOT NULL,
    subject_id  INT NOT NULL,
    UNIQUE KEY uniq_enrollment (student_id, subject_id),
    FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- One row per student per session. PRESENT comes from a QR scan or a manual override;
-- ABSENT is written when faculty finalize a session or override manually.
-- A student with no row yet is simply "not marked yet" in the live session view.
CREATE TABLE attendance (
    id               INT AUTO_INCREMENT PRIMARY KEY,
    student_id       INT NOT NULL,
    faculty_id       INT NOT NULL,
    subject_id       INT NOT NULL,
    class_id         INT NOT NULL,
    attendance_date  DATE NOT NULL,
    period           INT NOT NULL,
    status           ENUM('PRESENT', 'ABSENT') NOT NULL DEFAULT 'PRESENT',
    method           ENUM('QR', 'MANUAL') NOT NULL DEFAULT 'QR',
    marked_at        DATETIME NOT NULL,
    UNIQUE KEY uniq_attendance (student_id, subject_id, attendance_date, period),
    KEY idx_session (subject_id, attendance_date, period),
    FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
    FOREIGN KEY (faculty_id) REFERENCES faculty(id)  ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES subjects(id) ON DELETE CASCADE,
    FOREIGN KEY (class_id)   REFERENCES classes(id)  ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Sample data. Passwords: admin123 / faculty123 / student123, stored as salted
-- PBKDF2 hashes. CHANGE THESE before sharing or deploying the system.
-- Student qr_token values are secret HMAC keys (the QR itself rotates and never
-- contains them); fixed sample UUIDs are used here only so the demo is repeatable -
-- the app generates a fresh random one for every student created via the Admin screen.
-- ---------------------------------------------------------------------
INSERT INTO users (username, password_hash, full_name, role) VALUES
('admin',        'pbkdf2$120000$6qI2HzaBM0LWmqxKvVfOiA==$aIBJiEhG5gkwaEG70KhB7jz2DCXqeuUg2ToH+X2j4AU=', 'System Administrator', 'ADMIN'),
('FAC1001',      'pbkdf2$120000$cRCR5CyMRALIVf9WOrqvPA==$TiH/xOSifIAOE5Fn6clgP/DUrKFuyfV6D8NRVcBjBrY=', 'Dr. S. Kumar',          'FACULTY'),
('9923CS0001',   'pbkdf2$120000$iiTgXCvpMk8EZpAzLrGRRA==$4/IK4+ersfAjzS/HNeNrpO/RngcWHdfydSU9QfVBBfE=', 'Aravind R',             'STUDENT'),
('9923CS0002',   'pbkdf2$120000$SxifA2e3I+ViLXANRauLnA==$+QH42tJt75wcynHrH8XOHlZobzXkolq3hc4bjl3N/tk=', 'Divya S',               'STUDENT');

INSERT INTO classes (class_name, department, semester, section) VALUES
('II CSE (AI & ML) A', 'CSE (AI & ML)', 3, 'A');

INSERT INTO faculty (user_id, faculty_code, department) VALUES
(2, 'FAC1001', 'CSE (AI & ML)');

INSERT INTO students (user_id, register_number, department, semester, section, class_id, qr_token) VALUES
(3, '9923CS0001', 'CSE (AI & ML)', 3, 'A', 1, '11111111-1111-1111-1111-111111111111'),
(4, '9923CS0002', 'CSE (AI & ML)', 3, 'A', 1, '22222222-2222-2222-2222-222222222222');

INSERT INTO subjects (subject_code, subject_name, class_id, faculty_id) VALUES
('CSE203', 'Object Oriented Programming using Java', 1, 1);

INSERT INTO subject_enrollments (student_id, subject_id) VALUES
(1, 1),
(2, 1);
