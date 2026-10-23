package com.dartwatch.disclosure;

public enum DisclosureType {
    PERIODIC("A"), MATERIAL_EVENT("B"), ISSUANCE("C"), SHAREHOLDING("D"), OTHER("E"),
    EXTERNAL_AUDIT("F"), FUND("G"), ASSET_SECURITIZATION("H"), EXCHANGE("I"), FAIR_TRADE("J");

    private final String dartCode;

    DisclosureType(String dartCode) {
        this.dartCode = dartCode;
    }

    public String getDartCode() { return dartCode; }
}
