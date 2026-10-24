package com.dartwatch.opendart;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static com.dartwatch.support.DartFixtures.*;

class CorpCodeParserTests {
    private final CorpCodeParser parser = new CorpCodeParser();

    @Test
    void parsesZipAndPreservesCodesAndMissingStockCodes() throws Exception {
        var records = parser.parse(zip("CORPCODE.xml", companiesXml()));
        assertThat(records).hasSize(2);
        assertThat(records.getFirst().corpCode()).isEqualTo("00000001");
        assertThat(records.getFirst().stockCode()).isEqualTo("000001");
        assertThat(records.getFirst().corpName()).isEqualTo("테스트 기업");
        assertThat(records.getFirst().modifyDate()).hasToString("2026-10-23");
        assertThat(records.get(1).stockCode()).isNull();
    }

    @Test
    void absentStockElementAlsoMeansUnlisted() throws Exception {
        assertThat(parser.parse(zip("CORPCODE.xml", companiesXml().replace("<stock_code> </stock_code>", ""))).get(1).stockCode())
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../CORPCODE.xml", "/CORPCODE.xml", "dir/CORPCODE.xml", "CORPCODE.txt"})
    void rejectsUnexpectedZipPaths(String name) throws Exception {
        byte[] archive = zip(name, companiesXml());
        assertThatThrownBy(() -> parser.parse(archive)).isInstanceOf(OpenDartException.class);
    }

    @Test
    void rejectsDtdAndExternalEntities() throws Exception {
        String xml = "<!DOCTYPE result [<!ENTITY secret SYSTEM 'file:///must-not-be-read'>]>"
                + "<result><list><corp_name>&secret;</corp_name></list></result>";
        byte[] archive = zip("CORPCODE.xml", xml);
        assertThatThrownBy(() -> parser.parse(archive)).isInstanceOf(OpenDartException.class);
    }

    @Test
    void rejectsExpansionBeyondLimit() throws Exception {
        byte[] archive = zip("CORPCODE.xml", "x".repeat(CorpCodeParser.MAX_XML_BYTES + 1));
        assertThatThrownBy(() -> parser.parse(archive)).isInstanceOf(OpenDartException.class);
    }

    @Test
    void recognizesErrorXmlInsteadOfZipAndDoesNotExposeMessage() {
        byte[] body = "<result><status>010</status><message>sensitive upstream content</message></result>"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse(body)).isInstanceOfSatisfying(OpenDartException.class, ex -> {
            assertThat(ex.getKind()).isEqualTo(OpenDartException.Kind.AUTHENTICATION);
            assertThat(ex.getMessage()).doesNotContain("sensitive");
            assertThat(ex.getCause()).isNull();
        });
    }

    @Test
    void recognizesNoDataXml() {
        assertThat(parser.parse("<result><status>013</status><message>None</message></result>".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    @Test
    void malformedDatesAndTruncatedZipAreSafeProtocolErrors() throws Exception {
        byte[] archive = zip("CORPCODE.xml", companiesXml().replace("20261023", "20260230"));
        assertThatThrownBy(() -> parser.parse(archive)).isInstanceOf(OpenDartException.class);
        assertThatThrownBy(() -> parser.parse(new byte[] {'P', 'K', 1})).isInstanceOf(OpenDartException.class);
    }
}
