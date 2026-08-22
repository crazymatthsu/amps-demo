package com.demo.amps.quickfixj;

/**
 * What to do with tags (or NVFIX names) and enum values that are not in the
 * loaded QuickFIX/J dictionary.
 *
 * <p><b>Default: {@link #PASSTHROUGH}.</b> Unknown tags are copied onto the
 * other encoding with the same key they arrived as (numeric tag stays numeric
 * in NVFIX; an unknown name stays a name in FIX if it is not an integer).
 * Enum values that are neither a known code nor a known meaning are left
 * unchanged. Fields are never dropped solely because they are unknown.
 *
 * <p>This is the right policy for AMPS custom tags (user-defined range, e.g.
 * {@code 9001}) and for delta payloads that may carry venue-specific fields.
 */
public enum UnknownFieldPolicy {
    PASSTHROUGH
}
