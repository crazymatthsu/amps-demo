package com.demo.amps.connectors.config;

/**
 * What the source's string payload <em>is</em>, which is what selects the decoder.
 *
 * <p>Not the same question as {@code amps.message-type}, which selects the encoder and the
 * client URI. They are usually equal -- and when they are, and no transform touches the record,
 * the pipeline can pass the original bytes through untouched ({@code passthrough: AUTO}) --
 * but a connector that reads FIX and publishes {@code json} is exactly the translation this
 * framework exists to do.
 */
public enum SourceFormat {

    /** A JSON object; field names are keys, dotted paths address nested objects. */
    JSON,

    /** {@code tag=value} pairs separated by the connector's field separator; tags are numeric. */
    FIX,

    /** {@code name=value} pairs in FIX framing; tags are names rather than FIX tag numbers. */
    NVFIX,

    /** An opaque line. Decodes to the single field {@code text}, so it can never pass through. */
    TEXT;

    /**
     * Whether the payload is separator-delimited {@code name=value} pairs, i.e. whether
     * {@code field-separator} means anything for this format.
     *
     * @return {@code true} for {@link #FIX} and {@link #NVFIX}
     */
    public boolean delimited() {
        return this == FIX || this == NVFIX;
    }
}
