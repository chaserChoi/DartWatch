package com.dartwatch.opendart;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DartDisclosure(
        @JsonProperty("corp_code") String corpCode,
        @JsonProperty("corp_name") String corpName,
        @JsonProperty("rcept_no") String rceptNo,
        @JsonProperty("report_nm") String reportName,
        @JsonProperty("rcept_dt") String receiptDate,
        @JsonProperty("flr_nm") String filerName,
        @JsonProperty("rm") String remarks) {

    public LocalDate parsedReceiptDate() {
        return LocalDate.parse(receiptDate, DateTimeFormatter.BASIC_ISO_DATE);
    }

    public void validate(String expectedCorpCode, LocalDate from, LocalDate through) {
        if (!expectedCorpCode.equals(corpCode) || rceptNo == null || !rceptNo.matches("[0-9]{14}")
                || corpName == null || corpName.isBlank() || corpName.length() > 200
                || reportName == null || reportName.isBlank() || reportName.length() > 500
                || (filerName != null && filerName.length() > 255) || (remarks != null && remarks.length() > 1000)) {
            throw new OpenDartException(OpenDartException.Kind.INVALID_RESPONSE);
        }
        try {
            var date = parsedReceiptDate();
            if (date.isBefore(from) || date.isAfter(through)) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new OpenDartException(OpenDartException.Kind.INVALID_RESPONSE);
        }
    }
}
