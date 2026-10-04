package com.dch.sapstub;

public interface CandidateChangePublisher {

    void publish(CandidateChangedEvent event);
}
