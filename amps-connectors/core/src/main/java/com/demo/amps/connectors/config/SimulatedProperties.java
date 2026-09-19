package com.demo.amps.connectors.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Settings for the in-process generator {@code source.driver: SIMULATED} selects.
 *
 * <p>The point of a simulated source is that only the <em>reader</em> is fake: the records it
 * produces go through the same decoder, filter, transforms, key extraction, encoder, batcher
 * and AMPS client as a real feed's, so a demo or a test exercises everything except the
 * broker. That makes the template the interesting knob -- it is written in the connector's own
 * {@code format}, so a FIX connector's simulated records really are FIX.
 */
public class SimulatedProperties {

    /** Records per second. */
    @Min(1)
    private int rate = 5;

    /** Distinct keys to cycle through, bounding what a keyed SOW topic converges onto. */
    @Min(1)
    private int keys = 8;

    /**
     * The payload, with three placeholders: {@code &#123;&#123;key&#125;&#125;} (the cycling
     * {@code K-<n>}), {@code &#123;&#123;seq&#125;&#125;} (a running counter) and
     * {@code &#123;&#123;ts&#125;&#125;} (an ISO-8601 instant).
     *
     * <p>A FIX or NVFIX template writes {@code |} between its fields and the source swaps it
     * for the connector's {@code field-separator}: a literal SOH does not survive a YAML file.
     */
    @NotBlank
    private String template = "{\"id\":\"{{key}}\",\"seq\":{{seq}},\"ts\":\"{{ts}}\"}";

    public int getRate() {
        return rate;
    }

    public void setRate(int rate) {
        this.rate = rate;
    }

    public int getKeys() {
        return keys;
    }

    public void setKeys(int keys) {
        this.keys = keys;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }
}
