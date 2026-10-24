package com.dartwatch.support;

import com.dartwatch.opendart.DartDisclosure;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DartFixtures {
    private DartFixtures() { }

    public static byte[] zip(String name, String content) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    public static String companiesXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <result>
                  <list><corp_code>00000001</corp_code><corp_name>테스트 기업</corp_name>
                  <stock_code>000001</stock_code><modify_date>20261023</modify_date></list>
                  <list><corp_code>00000002</corp_code><corp_name>비상장 기업</corp_name>
                  <stock_code> </stock_code><modify_date>20261024</modify_date></list>
                </result>
                """;
    }

    public static DartDisclosure disclosure(String receipt, LocalDate date) {
        return new DartDisclosure("00000001", "테스트 기업", receipt, "Test report",
                date.format(DateTimeFormatter.BASIC_ISO_DATE), "Test filer", "정");
    }

    public static String item(String receipt, String date) {
        return """
                {"corp_code":"00000001","corp_name":"테스트 기업","stock_code":"000001",
                "rcept_no":"%s","report_nm":"Test report","rcept_dt":"%s","flr_nm":"Test filer","rm":"정"}
                """.formatted(receipt, date);
    }

    public static String page(int page, int size, int total, int pages, String items) {
        return """
                {"status":"000","message":"정상","page_no":%d,"page_count":%d,
                "total_count":%d,"total_page":%d,"list":[%s]}
                """.formatted(page, size, total, pages, items);
    }
}
