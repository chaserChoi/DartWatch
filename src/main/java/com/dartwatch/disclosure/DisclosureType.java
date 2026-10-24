package com.dartwatch.disclosure;

public enum DisclosureType {
    PERIODIC("A"), MATERIAL_EVENT("B"), ISSUANCE("C"), SHAREHOLDING("D"), OTHER("E"),
    EXTERNAL_AUDIT("F"), FUND("G"), ASSET_SECURITIZATION("H"), EXCHANGE("I"), FAIR_TRADE("J");

    private final String dartCode;

    DisclosureType(String dartCode) {
        this.dartCode = dartCode;
    }

    public String getDartCode() { return dartCode; }

    public boolean isMvpNotificationType() {
        return switch (this) {
            case PERIODIC, MATERIAL_EVENT, ISSUANCE, SHAREHOLDING, OTHER -> true;
            default -> false;
        };
    }

    public static DisclosureType mvpType(String code) {
        for (var type : values()) {
            if (type.isMvpNotificationType() && type.dartCode.equals(code)) return type;
        }
        throw new IllegalArgumentException("Notification type must be A..E");
    }

    public static DisclosureType fromDartCode(String code) {
        for (var type : values()) {
            if (type.dartCode.equals(code)) return type;
        }
        throw new IllegalArgumentException("Disclosure type must be A..J");
    }
}
