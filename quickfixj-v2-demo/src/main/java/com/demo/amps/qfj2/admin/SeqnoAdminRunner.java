package com.demo.amps.qfj2.admin;

import com.demo.amps.qfj2.seqno.SeqnoAdmin;
import com.demo.amps.qfj2.seqno.SeqnoSnapshot;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import quickfix.SessionID;

/**
 * The {@code seqno-admin} profile: inspect or rewrite sequence numbers, then exit.
 *
 * <pre>
 *   --seqno.action=show                            both sides, every session
 *   --seqno.action=set-file  --seqno.sender=N --seqno.target=M
 *   --seqno.action=set-amps  --seqno.sender=N --seqno.target=M
 *   --seqno.action=file-to-amps
 *   --seqno.action=amps-to-file
 *   --seqno.session=FIX.4.2:DROPCOPY->VENUE        limit to one session
 * </pre>
 *
 * <p>Either number may be omitted on the set actions; the other keeps its
 * current value. Exit code 0 on success, 1 on an error, 2 on bad usage.
 */
@Component
@Profile("seqno-admin")
public class SeqnoAdminRunner implements ApplicationRunner, ExitCodeGenerator {

    private final SeqnoAdmin admin;
    private final Environment environment;
    private final PrintStream out;
    private int exitCode;

    /** The one Spring uses; the other exists so a test can capture the output. */
    @Autowired
    public SeqnoAdminRunner(SeqnoAdmin admin, Environment environment) {
        this(admin, environment, System.out);
    }

    SeqnoAdminRunner(SeqnoAdmin admin, Environment environment, PrintStream out) {
        this.admin = admin;
        this.environment = environment;
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        String action = environment.getProperty("seqno.action", "show").trim().toLowerCase(Locale.ROOT);
        String session = environment.getProperty("seqno.session");
        OptionalInt sender = optionalInt("seqno.sender");
        OptionalInt target = optionalInt("seqno.target");
        try {
            List<SessionID> sessions = session == null || session.isBlank()
                    ? admin.sessions()
                    : List.of(admin.session(session.trim()));
            switch (action) {
                case "show" -> {
                    for (SessionID id : sessions) {
                        print(admin.show(id));
                    }
                }
                case "set-file" -> {
                    requireANumber(sender, target);
                    for (SessionID id : sessions) {
                        SeqnoAdmin.FileNumbers numbers = admin.setFile(id, sender, target);
                        out.printf("%s  file -> %s  (%s)%n", id, numbers.numbers(), numbers.senderSeqNumFile());
                    }
                }
                case "set-amps" -> {
                    requireANumber(sender, target);
                    for (SessionID id : sessions) {
                        SeqnoSnapshot snapshot = admin.setAmps(id, sender, target);
                        out.printf("%s  AMPS -> %s  (rev %d, %s)%n", id, snapshot.numbers(), snapshot.revision(),
                                snapshot.source());
                    }
                }
                case "file-to-amps" -> {
                    for (SessionID id : sessions) {
                        SeqnoSnapshot snapshot = admin.fileToAmps(id);
                        out.printf("%s  file %s -> AMPS  (rev %d)%n", id, snapshot.numbers(), snapshot.revision());
                    }
                }
                case "amps-to-file" -> {
                    for (SessionID id : sessions) {
                        SeqnoAdmin.FileNumbers numbers = admin.ampsToFile(id);
                        out.printf("%s  AMPS %s -> file  (%s)%n", id, numbers.numbers(), numbers.senderSeqNumFile());
                    }
                }
                default -> {
                    out.println("seqno-admin: unknown action '" + action + "'. One of: show, set-file, set-amps, "
                            + "file-to-amps, amps-to-file (with --seqno.sender=N --seqno.target=M "
                            + "--seqno.session=<id>)");
                    exitCode = 2;
                }
            }
        } catch (Exception e) {
            out.println("seqno-admin: " + e.getMessage());
            exitCode = 1;
        }
    }

    private void print(SeqnoAdmin.State state) {
        String file = state.file().map(SeqnoAdmin.FileNumbers::numbers).orElse("(no file)");
        String amps = state.amps().map(SeqnoSnapshot::numbers).orElse("(no checkpoint)");
        String detail = state.amps()
                .map(s -> "rev " + s.revision() + ", " + s.source() + ", " + s.updatedAt())
                .orElse("");
        String sync = state.file().isEmpty() || state.amps().isEmpty() ? "" : (state.inSync() ? "in sync" : "DIFFER");
        out.printf("%-34s file %-12s AMPS %-12s %-9s %s%n", state.sessionId(), file, amps, sync, detail);
        state.file().ifPresent(numbers -> out.printf("%-34s      %s%n", "", numbers.senderSeqNumFile()));
    }

    private OptionalInt optionalInt(String property) {
        String raw = environment.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return OptionalInt.empty();
        }
        int value = Integer.parseInt(raw.trim());
        if (value < 1) {
            throw new IllegalArgumentException(property + " must be at least 1, got " + value);
        }
        return OptionalInt.of(value);
    }

    private static void requireANumber(OptionalInt sender, OptionalInt target) {
        if (sender.isEmpty() && target.isEmpty()) {
            throw new IllegalArgumentException("give --seqno.sender=N and/or --seqno.target=M");
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
