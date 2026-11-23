package com.example.keycloak.tmppassword.sms;

public interface SmsSender {

    /**
     * Sends {@code message} to {@code phone}.
     *
     * @throws SmsException when the message could not be delivered to the provider
     */
    void send(String phone, String message) throws SmsException;

    class SmsException extends Exception {
        public SmsException(String message) {
            super(message);
        }

        public SmsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
