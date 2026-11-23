package com.example.keycloak.tmppassword;

import org.keycloak.Config;

/**
 * Server-wide settings, read from Keycloak SPI options, e.g.
 * {@code --spi-realm-restapi-extension--tmp-password--sms-url=https://...}.
 *
 * Placeholders use single braces ({phone}, {message}, {password}, ...) because
 * Keycloak's config system expands ${...} expressions itself.
 */
public record TmpPasswordConfig(
        String smsMode,
        String smsUrl,
        String smsMethod,
        String smsContentType,
        String smsBodyTemplate,
        String smsHeaderName,
        String smsHeaderValue,
        int smsTimeoutSeconds,
        String phoneAttribute,
        String messageTemplate,
        int passwordLength,
        boolean useSpecialChars,
        boolean temporary) {

    public static final String MODE_HTTP = "http";
    public static final String MODE_LOG = "log";

    public static TmpPasswordConfig from(Config.Scope c) {
        return new TmpPasswordConfig(
                c.get("sms-mode", MODE_LOG),
                c.get("sms-url"),
                c.get("sms-method", "POST"),
                c.get("sms-content-type", "application/json"),
                c.get("sms-body-template", "{\"to\":\"{phone}\",\"message\":\"{message}\"}"),
                c.get("sms-header-name"),
                c.get("sms-header-value"),
                c.getInt("sms-timeout-seconds", 10),
                c.get("phone-attribute", "phoneNumber"),
                c.get("message-template", "Your temporary password for {realm} is: {password}"),
                c.getInt("password-length", 10),
                c.getBoolean("password-special-chars", true),
                c.getBoolean("temporary", true));
    }
}
