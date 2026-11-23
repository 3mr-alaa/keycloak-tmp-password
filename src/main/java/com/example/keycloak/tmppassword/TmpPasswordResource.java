package com.example.keycloak.tmppassword;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.policy.PasswordPolicyManagerProvider;
import org.keycloak.policy.PolicyError;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resources.admin.AdminAuth;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.AdminRoot;
import org.keycloak.services.resources.admin.fgap.AdminPermissions;

import com.example.keycloak.tmppassword.sms.SmsSender;

/**
 * POST /realms/{realm}/tmp-password/users/{userId}
 *
 * Requires an admin bearer token (the same one the Admin Console uses) whose owner
 * may manage the target user. Generates a password, stores it (temporary by default:
 * the user must change it at next login, and it expires after expiration-minutes) and sends it to the user's phone by SMS.
 * The password is never returned in the response.
 */
public class TmpPasswordResource {

    private static final Logger LOG = Logger.getLogger(TmpPasswordResource.class);
    private static final int MAX_POLICY_ATTEMPTS = 5;

    private final KeycloakSession session;
    private final TmpPasswordConfig config;
    private final SmsSender smsSender;

    public TmpPasswordResource(KeycloakSession session, TmpPasswordConfig config, SmsSender smsSender) {
        this.session = session;
        this.config = config;
        this.smsSender = smsSender;
    }

    @POST
    @Path("users/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response generate(@PathParam("userId") String userId) {
        // The realm from the URL; authentication below switches the context to the token's realm.
        RealmModel realm = session.getContext().getRealm();

        AdminAuth auth;
        try {
            auth = AdminRoot.authenticateRealmAdminRequest(session);
        } catch (NotAuthorizedException e) {
            return error(Response.Status.UNAUTHORIZED, "Not authenticated");
        } finally {
            session.getContext().setRealm(realm);
        }

        RealmModel masterRealm = new RealmManager(session).getKeycloakAdministrationRealm();
        if (!auth.getRealm().equals(realm) && !auth.getRealm().equals(masterRealm)) {
            return error(Response.Status.FORBIDDEN, "Token realm cannot manage this realm");
        }

        UserModel user = session.users().getUserById(realm, userId);
        try {
            if (user == null) {
                // Only admins who may list users learn whether an id exists.
                AdminPermissions.evaluator(session, realm, auth).users().requireQuery();
                return error(Response.Status.NOT_FOUND, "User not found");
            }
            AdminPermissions.evaluator(session, realm, auth).users().requireManage(user);
        } catch (ForbiddenException e) {
            return error(Response.Status.FORBIDDEN, "You are not allowed to manage this user");
        }

        String phone = user.getFirstAttribute(config.phoneAttribute());
        if (phone == null || phone.isBlank()) {
            return error(Response.Status.BAD_REQUEST,
                    "User has no phone number (attribute '" + config.phoneAttribute() + "')");
        }
        phone = phone.trim();

        String password;
        try {
            password = generateValidPassword(realm, user);
        } catch (PolicyException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        }

        try {
            user.credentialManager().updateCredential(UserCredentialModel.password(password, false));
        } catch (ModelException e) {
            LOG.warnf(e, "Could not set temporary password for user %s", user.getId());
            return error(Response.Status.BAD_REQUEST, "Could not set password: " + e.getMessage());
        }
        if (config.temporary()) {
            user.addRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD);
        }

        Long expiresAt = null;
        if (config.expirationMinutes() > 0) {
            expiresAt = Time.currentTimeMillis() + config.expirationMinutes() * 60_000L;
            TmpPasswordExpiry.track(user, expiresAt);
        } else {
            TmpPasswordExpiry.clear(user);
        }

        String message = config.messageTemplate()
                .replace("{password}", password)
                .replace("{username}", user.getUsername())
                .replace("{minutes}", Integer.toString(config.expirationMinutes()))
                .replace("{realm}", realm.getDisplayName() != null ? realm.getDisplayName() : realm.getName());

        try {
            smsSender.send(phone, message);
        } catch (SmsSender.SmsException e) {
            LOG.warnf("Sending temporary password SMS for user %s failed: %s", user.getId(), e.getMessage());
            // Roll back the password change so the user is not left with a password nobody knows.
            session.getTransactionManager().setRollbackOnly();
            return error(Response.Status.BAD_GATEWAY, "Failed to send SMS: " + e.getMessage());
        }

        new AdminEventBuilder(realm, auth, session, session.getContext().getConnection())
                .operation(OperationType.ACTION)
                .resource(ResourceType.USER)
                .resourcePath("users", user.getId(), "tmp-password")
                .detail("phone", mask(phone))
                .success();

        LOG.infof("Temporary password sent by SMS to user %s (realm %s) by admin %s",
                user.getUsername(), realm.getName(), auth.getUser().getUsername());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "sent");
        body.put("phone", mask(phone));
        body.put("temporary", config.temporary());
        body.put("expiresAt", expiresAt == null ? null : Instant.ofEpochMilli(expiresAt).toString());
        body.put("expiresInMinutes", expiresAt == null ? null : config.expirationMinutes());
        return Response.ok(body).build();
    }

    private String generateValidPassword(RealmModel realm, UserModel user) throws PolicyException {
        PasswordPolicyManagerProvider policy = session.getProvider(PasswordPolicyManagerProvider.class);
        PolicyError lastError = null;
        for (int i = 0; i < MAX_POLICY_ATTEMPTS; i++) {
            String candidate = PasswordGenerator.generate(config.passwordLength(), config.useSpecialChars());
            lastError = policy.validate(realm, user, candidate);
            if (lastError == null) {
                return candidate;
            }
        }
        throw new PolicyException("Generated password does not satisfy the realm password policy ("
                + lastError.getMessage() + "). Increase password-length.");
    }

    static String mask(String phone) {
        int visible = Math.min(3, phone.length());
        return "*".repeat(phone.length() - visible) + phone.substring(phone.length() - visible);
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status)
                .entity(Map.of("error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static class PolicyException extends Exception {
        PolicyException(String message) {
            super(message);
        }
    }
}
