package com.dch.smartrecruters.messaging;

import com.dch.smartrecruters.service.CandidateDeltaService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Transport adapter only. Retries, dead lettering and offset handling are configured on the
 * listener container ({@code DeltaKafkaConfiguration}); all processing is in {@link CandidateDeltaService}.
 */
@Component
public class CandidateChangedListener {

    public static final String LISTENER_ID = "candidate-changes-listener";

    private final CandidateDeltaService candidateDeltaService;

    public CandidateChangedListener(CandidateDeltaService candidateDeltaService) {
        this.candidateDeltaService = candidateDeltaService;
    }

    @KafkaListener(
            id = LISTENER_ID,
            idIsGroup = false,
            topics = "${migration.kafka.candidate-changes-topic}"
    )
    public void onCandidateChanged(CandidateChangedEvent event) {
        candidateDeltaService.process(event);
    }
}
