package com.demo.amps.utils;

/** One operator tool. The shell wrappers in {@code utils/bin} are thin fronts for these. */
public interface Tool {

    /** Subcommand name, as {@link UtilsRunner} dispatches it. */
    String name();

    /** One line, shown in the catalogue. */
    String summary();

    /** Multi-line option help, shown for --help and on any usage error. */
    String usage();

    void run(Args args) throws Exception;
}
