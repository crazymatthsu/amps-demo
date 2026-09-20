package com.demo.amps.connectors.transform;

import com.demo.amps.connectors.source.SourceRecord;
import java.util.Map;

/**
 * A per-record rewrite of the decoded field map.
 *
 * <p>Every {@code transforms:} step is one of these -- the built-in kinds ({@code keep},
 * {@code rename}, {@code derive}…) are compiled into them by {@link TransformChain}, and a
 * {@code bean:} step names one the application declared. That is the seam an
 * {@code amps-connectors/apps/} application exists for: the enrichment that is genuinely code
 * -- a reference-data lookup, a decode this framework does not know -- costs a bean and one
 * line of YAML rather than a fork of the pipeline.
 *
 * <p>The contract:
 *
 * <ul>
 *   <li>Implementations must be <strong>stateless and thread-safe</strong>: one instance serves
 *       every connector that names it, and it runs on the source's reader thread -- of which a
 *       TCP connector in {@code LISTEN} mode has one per connected client.</li>
 *   <li>A {@code null} return <strong>drops</strong> the record. The connector counts that as
 *       dropped rather than rejected: it is a decision, not a failure.</li>
 *   <li>Transforms also see {@link SourceRecord.Action#DELETE} records, because a delete's key
 *       is extracted from its fields the same way an upsert's is. A transform that derives a
 *       key field has to derive it for deletes too, or the removal cannot be addressed.</li>
 *   <li>The map passed in must not be mutated; return a new one. The chain hands each step its
 *       own copy, and a transform that writes into the map it was given would be editing the
 *       evidence the next step is reading.</li>
 * </ul>
 *
 * <p>A bean that wants to know <em>which</em> connector it is serving -- to name it in an
 * alert, say -- overrides {@link #bind(TransformContext)}: the registry calls it once per
 * connector while resolving that connector's {@code bean:} steps, and folds the returned
 * transform into that connector's chain. The bean itself stays one shared, stateless
 * instance; the bound copy is the per-connector view, and it must be as thread-safe as the
 * bean is. The default returns {@code this}, so a transform that does not care never sees
 * the context.
 */
@FunctionalInterface
public interface RecordTransform {

    /**
     * @param record the record the fields were decoded from, for its action, key and attributes
     * @param fields the fields so far, in wire order
     * @return the fields to carry on with, or {@code null} to drop the record
     */
    Map<String, Object> apply(SourceRecord record, Map<String, Object> fields);

    /**
     * The view of this transform for one connector.
     *
     * @param context the connector the transform is being resolved for, its registry and
     *     where its alerts go
     * @return the transform to fold into that connector's chain; this instance by default
     */
    default RecordTransform bind(TransformContext context) {
        return this;
    }
}
