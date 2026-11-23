package com.example.keycloak.tmppassword.sms;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * Sends SMS through any HTTP API. The request is described by configuration:
 * URL, method, content type, body template and one optional auth header.
 * The placeholders {phone} and {message} are replaced in the URL (URL-encoded)
 * and in the body (escaped for JSON or URL-encoded for forms).
 */
public class HttpSmsSender implements SmsSender {

    private static final Logger LOG = Logger.getLogger(HttpSmsSender.class);

    private final HttpClient client;
    private final String url;
    private final String method;
    private final String contentType;
    private final String bodyTemplate;
    private final String headerName;
    private final String headerValue;
    private final Duration timeout;

    public HttpSmsSender(HttpClient client, String url, String method, String contentType,
                         String bodyTemplate, String headerName, String headerValue, Duration timeout) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("sms-url is required when sms-mode=http");
        }
        this.client = client;
        this.url = url;
        this.method = method.toUpperCase();
        this.contentType = contentType;
        this.bodyTemplate = bodyTemplate;
        this.headerName = headerName;
        this.headerValue = headerValue;
        this.timeout = timeout;
    }

    @Override
    public void send(String phone, String message) throws SmsException {
        HttpRequest request = buildRequest(phone, message);
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                LOG.warnf("SMS provider returned HTTP %d: %s", status, abbreviate(response.body()));
                throw new SmsException("SMS provider returned HTTP " + status);
            }
            LOG.debugf("SMS provider accepted message, HTTP %d", status);
        } catch (IOException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new SmsException("Could not reach SMS provider: " + reason, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsException("Interrupted while sending SMS", e);
        }
    }

    HttpRequest buildRequest(String phone, String message) {
        Map<String, String> values = Map.of("phone", phone, "message", message);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(render(url, values, Escaping.URL)))
                .timeout(timeout);

        if (headerName != null && !headerName.isBlank() && headerValue != null) {
            builder.header(headerName, headerValue);
        }

        boolean hasBody = !method.equals("GET") && bodyTemplate != null && !bodyTemplate.isBlank();
        if (hasBody) {
            Escaping escaping = contentType.toLowerCase().contains("json") ? Escaping.JSON
                    : contentType.toLowerCase().contains("x-www-form-urlencoded") ? Escaping.URL
                    : Escaping.NONE;
            builder.header("Content-Type", contentType);
            builder.method(method, HttpRequest.BodyPublishers.ofString(
                    render(bodyTemplate, values, escaping), StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    enum Escaping { NONE, JSON, URL }

    static String render(String template, Map<String, String> values, Escaping escaping) {
        String result = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", escape(e.getValue(), escaping));
        }
        return result;
    }

    static String escape(String value, Escaping escaping) {
        return switch (escaping) {
            case URL -> URLEncoder.encode(value, StandardCharsets.UTF_8);
            case JSON -> jsonEscape(value);
            case NONE -> value;
        };
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
