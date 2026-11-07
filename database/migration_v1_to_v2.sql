-- Upgrade an EXISTING version-1 database in place (keeps all your data).
-- New installs should use schema.sql instead.
-- Safe to re-run: each step is skipped if it has already been applied, so it
-- will not fail with "Duplicate column" / "Duplicate key name" on a v2 database.
USE qr_attendance;

-- 1. room for salted PBKDF2 hashes. Old SHA-256 hashes keep working and are upgraded
--    automatically the first time each user logs in. (MODIFY is harmless to repeat.)
ALTER TABLE users MODIFY password_hash VARCHAR(255) NOT NULL;

-- 2. absent marking + manual override
ALTER TABLE attendance MODIFY status ENUM('PRESENT', 'ABSENT') NOT NULL DEFAULT 'PRESENT';

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE attendance ADD COLUMN method ENUM(''QR'', ''MANUAL'') NOT NULL DEFAULT ''QR'' AFTER status',
        'SELECT ''attendance.method already exists - skipped'' AS info')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attendance' AND COLUMN_NAME = 'method'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. faster reports
SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE attendance ADD KEY idx_session (subject_id, attendance_date, period)',
        'SELECT ''idx_session already exists - skipped'' AS info')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attendance' AND INDEX_NAME = 'idx_session'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
