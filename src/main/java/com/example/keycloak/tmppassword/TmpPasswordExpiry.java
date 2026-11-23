package com.example.keycloak.tmppassword;

import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.timer.ScheduledTask;

/**
 * Expiry of generated passwords.
 *
 * When a temporary password is generated, three attributes are stored on the user:
 * a marker, the expiry time and the creation time of the password credential.
 * A scheduled task looks at marked users and:
 * <ul>
 *   <li>if the user's password is no longer the generated one (the user changed it,
 *       or an admin reset it), it just removes the attributes;</li>
 *   <li>if the generated password is still there and has expired, it deletes the
 *       password, so it can no longer be used to log in.</li>
 * </ul>
 */
public class TmpPasswordExpiry implements ScheduledTask {

    public static final String ATTR_MARKER = "tmp-password";
    public static final String ATTR_EXPIRES_AT = "tmp-password-expires-at";
    public static final String ATTR_CREATED_AT = "tmp-password-created-at";
    static final String MARKER_VALUE = "pending";

    private static final Logger LOG = Logger.getLogger(TmpPasswordExpiry.class);

    /** Records that the user's current password expires at {@code expiresAtMillis}. */
    public static void track(UserModel user, long expiresAtMillis) {
        Optional<CredentialModel> password = currentPassword(user);
        if (password.isEmpty() || password.get().getCreatedDate() == null) {
            clear(user);
            return;
        }
        user.setSingleAttribute(ATTR_MARKER, MARKER_VALUE);
        user.setSingleAttribute(ATTR_EXPIRES_AT, Long.toString(expiresAtMillis));
        user.setSingleAttribute(ATTR_CREATED_AT, Long.toString(password.get().getCreatedDate()));
    }

    public static void clear(UserModel user) {
        user.removeAttribute(ATTR_MARKER);
        user.removeAttribute(ATTR_EXPIRES_AT);
        user.removeAttribute(ATTR_CREATED_AT);
    }

    @Override
    public void run(KeycloakSession session) {
        long now = Time.currentTimeMillis();
        List<RealmModel> realms = session.realms().getRealmsStream().toList();
        for (RealmModel realm : realms) {
            // User storage lookups require the session to be bound to the realm.
            session.getContext().setRealm(realm);
            List<UserModel> users = session.users()
                    .searchForUserByUserAttributeStream(realm, ATTR_MARKER, MARKER_VALUE)
                    .toList();
            for (UserModel user : users) {
                process(realm, user, now);
            }
        }
    }

    static void process(RealmModel realm, UserModel user, long now) {
        long expiresAt = parse(user.getFirstAttribute(ATTR_EXPIRES_AT));
        long createdAt = parse(user.getFirstAttribute(ATTR_CREATED_AT));
        Optional<CredentialModel> password = currentPassword(user);

        boolean stillGenerated = password.isPresent()
                && password.get().getCreatedDate() != null
                && password.get().getCreatedDate() == createdAt;

        if (!stillGenerated || expiresAt <= 0) {
            // Password was changed since (or data is broken): nothing to expire.
            clear(user);
            return;
        }
        if (now >= expiresAt) {
            user.credentialManager().removeStoredCredentialById(password.get().getId());
            clear(user);
            LOG.infof("Temporary password of user %s (realm %s) expired and was removed",
                    user.getUsername(), realm.getName());
        }
    }

    private static Optional<CredentialModel> currentPassword(UserModel user) {
        return user.credentialManager()
                .getStoredCredentialsByTypeStream(PasswordCredentialModel.TYPE)
                .findFirst();
    }

    private static long parse(String value) {
        try {
            return value == null ? -1 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public String getTaskName() {
        return "tmp-password-expiry";
    }
}
