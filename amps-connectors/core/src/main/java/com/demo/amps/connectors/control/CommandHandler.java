package com.demo.amps.connectors.control;

/**
 * What one {@code command} does.
 *
 * <p>The extension point of the control channel: the framework ships {@link ReloadCommand}
 * and {@link StatusCommand}, and an application adds a {@code CommandHandler} bean per
 * command of its own -- "flush the cache", "switch the venue" -- which the
 * {@link CommandDispatcher} collects by {@link #command() name}. A bean whose name matches a
 * built-in replaces it, so an application that wants {@code reload} to mean something more
 * than "reload the resources" can say so without a second word for it.
 *
 * <p>{@link #handle} runs on the control source's reader thread, one command at a time, and
 * may throw: the dispatcher logs the failure, raises {@code COMMAND_FAILED} with the
 * exception's message, and carries on with the next command. What it must not do is block
 * for long -- a reload that takes a minute is a minute during which no other command is
 * read.
 */
public interface CommandHandler {

    /** The {@code command} value this handler answers to, e.g. {@code reload}. */
    String command();

    /**
     * Carry out the command.
     *
     * @param command the parsed command; its {@code target}, {@code requestId} and
     *     {@code args} mean whatever this handler says they mean
     * @param context what a handler needs: the application's name, its resources, its
     *     connectors, and where to raise
     * @throws Exception if the command could not be carried out; reported as
     *     {@code COMMAND_FAILED}
     */
    void handle(ControlCommand command, CommandContext context) throws Exception;
}
