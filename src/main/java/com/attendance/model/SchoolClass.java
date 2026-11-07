package com.attendance.model;

/** A class group, e.g. "II CSE(AI&ML) A" - department + semester + section. */
public class SchoolClass {
    private final int id;
    private final String className;
    private final String department;
    private final int semester;
    private final String section;

    public SchoolClass(int id, String className, String department, int semester, String section) {
        this.id = id;
        this.className = className;
        this.department = department;
        this.semester = semester;
        this.section = section;
    }

    public int getId() { return id; }
    public String getClassName() { return className; }
    public String getDepartment() { return department; }
    public int getSemester() { return semester; }
    public String getSection() { return section; }

    @Override
    public String toString() {
        return className;
    }
}
