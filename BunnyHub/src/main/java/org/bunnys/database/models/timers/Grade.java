package org.bunnys.database.models.timers;

public enum Grade {
    A_PLUS("A+", 4.0), A("A", 4.0), A_MINUS("A-", 3.7),
    B_PLUS("B+", 3.3), B("B", 3.0), B_MINUS("B-", 2.7),
    C_PLUS("C+", 2.3), C("C", 2.0), C_MINUS("C-", 1.7),
    D_PLUS("D+", 1.3), D("D", 1.0),
    F("F", 0.0), W("Withdraw", 0.0), P("Pass", 0.0);

    private final String stringValue;
    private final double gpaValue;

    Grade(String stringValue, double gpaValue) {
        this.stringValue = stringValue;
        this.gpaValue = gpaValue;
    }

    public String getStringValue() {
        return stringValue;
    }

    public double getGpaValue() {
        return gpaValue;
    }

    public static Grade fromString(String text) {
        for (Grade g : Grade.values())
            if (g.stringValue.equalsIgnoreCase(text))
                return g;
        return null;
    }
}