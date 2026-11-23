package com.example.keycloak.tmppassword.sms;

import org.jboss.logging.Logger;

/**
 * Development-only sender: writes the SMS to the server log instead of sending it.
 * Never use in production, the log will contain the temporary password.
 */
public class LogSmsSender implements SmsSender {

    private static final Logger LOG = Logger.getLogger(LogSmsSender.class);

    @Override
    public void send(String phone, String message) {
        LOG.warnf("[tmp-password][sms-mode=log] SMS to %s: %s", phone, message);
    }
}
