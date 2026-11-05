package com.adminmod.server.audit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.DoubleSupplier;

import com.adminmod.common.audit.AuditCategory;
import com.adminmod.common.audit.AuditEvent;
import com.adminmod.common.audit.AuditLog;
import com.adminmod.common.audit.AuditQuery;
import com.adminmod.common.audit.AuditSeverity;
import com.adminmod.common.config.AdminConfig;

/**
 * Server-side front door to the audit log.
 *
 * <p>Every subsystem reports through one of the named methods here rather than building an
 * {@link AuditEvent} itself, so the category and severity of each kind of event is decided in one
 * place and the call sites stay readable. The methods are cheap: they build a small record and hand
 * it to {@link AuditLog}, which does the queueing.
 *
 * <p>Two privacy rules are enforced here rather than trusted to callers:
 *
 * <ul>
 *   <li>A command is recorded by its name only, never its arguments, because arguments routinely
 *       carry a password, a token or an address.
 *   <li>Player chat is recorded only when the operator has explicitly turned it on, and only public
 *       chat reaches this class at all; private messages never do.
 * </ul>
 */
public final class AuditManager implements AutoCloseable {

    private final AuditLog log;
    private final AdminConfig config;

    public AuditManager(Path directory, AdminConfig config, DoubleSupplier msptSupplier) {
        this.config = config;
        this.log = new AuditLog(directory, config, msptSupplier);
    }

    public void start() throws IOException {
        log.start();
    }

    /** Applies a hot reload: retention windows, enabled categories and codec settings. */
    public void reload() {
        log.reload();
    }

    public AuditLog log() {
        return log;
    }

    // ------------------------------------------------------------------ players

    public void join(String name, String uuid, String address) {
        log.record(AuditEvent.builder(AuditCategory.PLAYER, AuditSeverity.INFO)
                .actor(name, uuid).subject(uuid).field("address", address).detail("joined").build());
    }

    public void leave(String name, String uuid, long sessionMillis) {
        log.record(AuditEvent.builder(AuditCategory.PLAYER, AuditSeverity.INFO)
                .actor(name, uuid).subject(uuid)
                .field("sessionSeconds", sessionMillis / 1000L).detail("left").build());
    }

    public void death(String name, String uuid, String cause) {
        log.record(AuditEvent.builder(AuditCategory.PLAYER, AuditSeverity.INFO)
                .actor(name, uuid).subject(uuid).field("cause", cause).detail("died").build());
    }

    public void respawn(String name, String uuid) {
        log.record(AuditEvent.builder(AuditCategory.PLAYER, AuditSeverity.INFO)
                .actor(name, uuid).subject(uuid).detail("respawned").build());
    }

    // ------------------------------------------------------------------ world

    public void blockBreak(String name, String uuid, String blockId, int x, int y, int z) {
        log.record(AuditEvent.builder(AuditCategory.BLOCK, AuditSeverity.INFO)
                .actor(name, uuid).field(AuditEvent.KEY_BLOCK, blockId)
                .field(AuditEvent.KEY_POSITION, x + "," + y + "," + z).detail("broke block").build());
    }

    public void containerOpened(String name, String uuid, String containerId, String position) {
        log.record(AuditEvent.builder(AuditCategory.CONTAINER, AuditSeverity.INFO)
                .actor(name, uuid).field(AuditEvent.KEY_CONTAINER, containerId)
                .field(AuditEvent.KEY_POSITION, position).detail("opened container").build());
    }

    // ------------------------------------------------------------------ commands and staff

    /**
     * Records a command by name only.
     *
     * <p>The arguments are deliberately dropped: {@code /login hunter2} and a command carrying a
     * token would otherwise write the secret to disk.
     */
    public void command(String actorName, String actorUuid, String command, boolean remote) {
        String name = command == null ? "" : command.trim();
        int space = name.indexOf(' ');
        if (space >= 0) {
            name = name.substring(0, space);
        }
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        log.record(AuditEvent.builder(AuditCategory.COMMAND, AuditSeverity.INFO)
                .actor(actorName, actorUuid).field(AuditEvent.KEY_COMMAND, name)
                .field("remote", remote).detail("ran command").build());
    }

    public void moderation(String staffName, String staffUuid, String action, String subject, String reason) {
        log.record(AuditEvent.builder(AuditCategory.MODERATION, AuditSeverity.WARNING)
                .actor(staffName, staffUuid).subject(subject).field("action", action)
                .field("reason", reason).detail("moderation action").build());
    }

    public void permission(String staffName, String staffUuid, String action, String subject) {
        log.record(AuditEvent.builder(AuditCategory.PERMISSION, AuditSeverity.HIGH)
                .actor(staffName, staffUuid).subject(subject).field("action", action)
                .detail("permission change").build());
    }

    public void config(String actorName, String key, String value) {
        log.record(AuditEvent.builder(AuditCategory.CONFIG, AuditSeverity.NOTICE)
                .actor(actorName).field("key", key).field("value", value).detail("configuration changed").build());
    }

    public void security(String actorName, String actorUuid, AuditSeverity severity, String detail) {
        log.record(AuditEvent.builder(AuditCategory.SECURITY, severity)
                .actor(actorName, actorUuid).detail(detail).build());
    }

    public void server(String detail) {
        log.record(AuditEvent.builder(AuditCategory.SERVER, AuditSeverity.INFO).detail(detail).build());
    }

    public void protection(String subject, String kind, String detail) {
        log.record(AuditEvent.builder(AuditCategory.ANTICHEAT, AuditSeverity.WARNING)
                .subject(subject).field("kind", kind).detail(detail).build());
    }

    /** Records a public chat message, only when the operator opted in. */
    public void chat(String name, String uuid, String message) {
        if (!config.audit.recordChat) {
            return;
        }
        log.record(AuditEvent.builder(AuditCategory.CHAT, AuditSeverity.INFO)
                .actor(name, uuid).field("message", message).detail("public chat").build());
    }

    // ------------------------------------------------------------------ queries

    public String searchJson(AuditQuery query) {
        return log.searchJson(query);
    }

    public String statusJson() {
        return log.statusJson();
    }

    public String archivesJson() {
        return log.archivesJson();
    }

    @Override
    public void close() {
        log.close();
    }
}
