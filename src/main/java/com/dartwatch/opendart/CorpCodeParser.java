package com.dartwatch.opendart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.springframework.stereotype.Component;

@Component
public class CorpCodeParser {
    static final int MAX_XML_BYTES = 64 * 1024 * 1024;

    public List<CorpRecord> parse(byte[] response) {
        try {
            if (response.length >= 2 && response[0] == 'P' && response[1] == 'K') {
                try (var zip = new ZipInputStream(new ByteArrayInputStream(response))) {
                    var entry = zip.getNextEntry();
                    // Never extract to disk. Only the documented, single root XML entry is accepted.
                    if (entry == null || entry.isDirectory() || !"CORPCODE.xml".equalsIgnoreCase(entry.getName())) {
                        throw new OpenDartException(OpenDartException.Kind.INVALID_RESPONSE);
                    }
                    byte[] xml = zip.readNBytes(MAX_XML_BYTES + 1);
                    if (xml.length > MAX_XML_BYTES || zip.getNextEntry() != null) {
                        throw new OpenDartException(OpenDartException.Kind.INVALID_RESPONSE);
                    }
                    return parseXml(xml);
                }
            }
            // Authentication / quota errors can be returned as XML instead of ZIP.
            return parseXml(response);
        } catch (OpenDartException ex) {
            throw ex;
        } catch (IOException | XMLStreamException | RuntimeException ex) {
            throw new OpenDartException(OpenDartException.Kind.INVALID_RESPONSE);
        }
    }

    private List<CorpRecord> parseXml(byte[] xml) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External entities are disabled");
        });
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
        List<CorpRecord> records = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        Map<String, String> fields = null;
        String status = null;
        String message = null;
        boolean rootSeen = false;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw new XMLStreamException("DTD and entities are disabled");
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (!rootSeen) {
                        if (!"result".equals(name)) throw new XMLStreamException("Invalid root");
                        rootSeen = true;
                    } else if ("list".equals(name)) {
                        if (fields != null) throw new XMLStreamException("Nested record");
                        fields = new HashMap<>();
                    } else if (Set.of("corp_code", "corp_name", "stock_code", "modify_date").contains(name)) {
                        if (fields == null) throw new XMLStreamException("Invalid record");
                        fields.put(name, reader.getElementText().strip());
                    } else if ("status".equals(name)) {
                        status = reader.getElementText().strip();
                    } else if ("message".equals(name)) {
                        message = reader.getElementText();
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "list".equals(reader.getLocalName())) {
                    if (fields == null || records.size() >= 500000) throw new XMLStreamException("Invalid record count");
                    var record = new CorpRecord(fields.get("corp_code"), fields.get("corp_name"), fields.get("stock_code"),
                            LocalDate.parse(fields.get("modify_date"), DateTimeFormatter.BASIC_ISO_DATE));
                    if (!codes.add(record.corpCode())) throw new XMLStreamException("Duplicate corporation code");
                    records.add(record);
                    fields = null;
                }
            }
            if (status != null && OpenDartException.isEmpty(status, message)) return List.of();
            if (!rootSeen || fields != null || records.isEmpty()) throw new XMLStreamException("Missing directory");
            return List.copyOf(records);
        } finally {
            reader.close();
        }
    }
}
