package com.example.keycloak.tmppassword;

import java.net.http.HttpClient;
import java.time.Duration;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.timer.TimerProvider;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

import com.example.keycloak.tmppassword.sms.HttpSmsSender;
import com.example.keycloak.tmppassword.sms.LogSmsSender;
import com.example.keycloak.tmppassword.sms.SmsSender;

/**
 * Registers {@code /realms/{realm}/tmp-password}.
 */
public class TmpPasswordResourceProviderFactory implements RealmResourceProviderFactory {

    public static final String ID = "tmp-password";

    private static final Logger LOG = Logger.getLogger(TmpPasswordResourceProviderFactory.class);

    private TmpPasswordConfig config;
    private SmsSender smsSender;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public void init(Config.Scope scope) {
        config = TmpPasswordConfig.from(scope);
        if (TmpPasswordConfig.MODE_HTTP.equalsIgnoreCase(config.smsMode())) {
            Duration timeout = Duration.ofSeconds(config.smsTimeoutSeconds());
            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
            smsSender = new HttpSmsSender(client, config.smsUrl(), config.smsMethod(), config.smsContentType(),
                    config.smsBodyTemplate(), config.smsHeaderName(), config.smsHeaderValue(), timeout);
            LOG.infof("tmp-password: SMS via HTTP %s %s", config.smsMethod(), config.smsUrl());
        } else {
            smsSender = new LogSmsSender();
            LOG.warn("tmp-password: sms-mode=log, SMS messages (including passwords) are written to the log. "
                    + "Do not use this in production.");
        }
    }

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new TmpPasswordResourceProvider(session, config, smsSender);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        if (config.expirationMinutes() <= 0) {
            return;
        }
        long intervalMillis = Math.max(5, config.cleanupIntervalSeconds()) * 1000L;
        factory.register(event -> {
            if (event instanceof PostMigrationEvent) {
                KeycloakModelUtils.runJobInTransaction(factory, session -> {
                    TimerProvider timer = session.getProvider(TimerProvider.class);
                    TmpPasswordExpiry task = new TmpPasswordExpiry();
                    timer.scheduleTask(task, intervalMillis, task.getTaskName());
                });
                LOG.infof("tmp-password: generated passwords expire after %d minutes (checked every %d s)",
                        config.expirationMinutes(), intervalMillis / 1000);
            }
        });
    }

    @Override
    public void close() {
    }
}
