package com.demo.amps.connectors.control;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code reload}: load a resource again, now.
 *
 * <pre>{@code
 * {"command":"reload","target":"instruments"}     one resource, by name
 * {"command":"reload","target":"all"}             every reloadable resource
 * {"command":"reload"}                            the same
 * }</pre>
 *
 * <p>The reason the control channel exists: a lookup table on a five-minute timer is a
 * table that is five minutes stale after the reference data changed, and the person who
 * changed it knows exactly when. The work is the registry's ({@code reload(name)} /
 * {@code reloadAll()}), synchronised there with the timer so a command and a tick never
 * load twice at once; what this class adds is the spelling of {@code target}.
 *
 * <p>A resource that does not exist, or is not reloadable, or throws while reloading, is a
 * thrown exception here -- and a {@code COMMAND_FAILED} alert from the dispatcher, with the
 * registry's message in it: which name it did not know and which names it does, or what the
 * database said. {@code all} reports every failure at once rather than stopping at the
 * first, because the resources are independent and the operator wants the whole picture.
 */
public final class ReloadCommand implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ReloadCommand.class);

    /** The command name, {@code reload}. */
    public static final String NAME = "reload";

    /** The target that means every reloadable resource, which a blank target also means. */
    public static final String ALL = "all";

    @Override
    public String command() {
        return NAME;
    }

    @Override
    public void handle(ControlCommand command, CommandContext context) {
        String target = command.target();
        if (target == null || target.isBlank() || ALL.equalsIgnoreCase(target.trim())) {
            List<String> reloaded = context.resources().reloadAll();
            log.info("[control] reloaded {} resource(s): {}", reloaded.size(), reloaded);
            return;
        }
        context.resources().reload(target.trim());
    }
}
