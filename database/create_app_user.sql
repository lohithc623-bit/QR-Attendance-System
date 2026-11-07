-- Run ONCE as the MySQL admin, after schema.sql. Replace the password first!
-- The app then connects as this least-privilege user instead of root.
CREATE USER IF NOT EXISTS 'qr_app'@'localhost' IDENTIFIED BY 'REPLACE_WITH_A_LONG_RANDOM_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON qr_attendance.* TO 'qr_app'@'localhost';
FLUSH PRIVILEGES;
