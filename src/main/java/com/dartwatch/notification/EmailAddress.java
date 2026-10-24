package com.dartwatch.notification;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Locale;

public final class EmailAddress {
    private EmailAddress() { }

    public static String normalize(String value) {
        if (value == null || value.isBlank() || value.length() > 320 || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("A single email address is required");
        }
        try {
            var addresses = InternetAddress.parse(value.strip(), true);
            if (addresses.length != 1 || addresses[0].getPersonal() != null) throw new AddressException();
            addresses[0].validate();
            return addresses[0].getAddress().toLowerCase(Locale.ROOT);
        } catch (AddressException ex) {
            throw new IllegalArgumentException("A single email address is required");
        }
    }
}
