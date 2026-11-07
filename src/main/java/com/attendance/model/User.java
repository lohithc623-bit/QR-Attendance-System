package com.attendance.model;

/** A row in the `users` table - login credentials + role. */
public class User {
    private final int id;
    private final String username;
    private final String passwordHash;
    private final String fullName;
    private final Role role;

    public User(int id, String username, String passwordHash, String fullName, Role role) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
    }

    public int getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
}
