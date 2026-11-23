package com.example.keycloak.tmppassword;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

import com.example.keycloak.tmppassword.sms.SmsSender;

public class TmpPasswordResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;
    private final TmpPasswordConfig config;
    private final SmsSender smsSender;

    public TmpPasswordResourceProvider(KeycloakSession session, TmpPasswordConfig config, SmsSender smsSender) {
        this.session = session;
        this.config = config;
        this.smsSender = smsSender;
    }

    @Override
    public Object getResource() {
        return new TmpPasswordResource(session, config, smsSender);
    }

    @Override
    public void close() {
    }
}
