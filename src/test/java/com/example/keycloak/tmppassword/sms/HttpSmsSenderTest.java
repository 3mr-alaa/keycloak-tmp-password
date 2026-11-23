package com.example.keycloak.tmppassword.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HttpSmsSenderTest {

    @Test
    void escapesJsonBody() {
        String body = HttpSmsSender.render("{\"to\":\"{phone}\",\"text\":\"{message}\"}",
                Map.of("phone", "+20100", "message", "pw: a\"b\\c\nd"), HttpSmsSender.Escaping.JSON);
        assertEquals("{\"to\":\"+20100\",\"text\":\"pw: a\\\"b\\\\c\\nd\"}", body);
    }

    @Test
    void urlEncodesFormBodyAndUrl() {
        String body = HttpSmsSender.render("to={phone}&text={message}",
                Map.of("phone", "+20100", "message", "pw: a&b=c"), HttpSmsSender.Escaping.URL);
        assertEquals("to=%2B20100&text=pw%3A+a%26b%3Dc", body);
    }

    @Test
    void buildsRequestWithHeaderAndRenderedUrl() {
        HttpSmsSender sender = new HttpSmsSender(HttpClient.newHttpClient(),
                "https://sms.example.com/send?to={phone}", "post", "application/json",
                "{\"m\":\"{message}\"}", "Authorization", "Bearer x", Duration.ofSeconds(5));
        HttpRequest request = sender.buildRequest("+20100", "hi");
        assertEquals("https://sms.example.com/send?to=%2B20100", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("Bearer x", request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
    }
}
