package com.dch.smartrecruters.service;

import com.dch.smartrecruters.client.SapClient;
import com.dch.smartrecruters.client.SmartRecruitersClient;
import com.dch.smartrecruters.mapper.CandidateMapper;
import com.dch.smartrecruters.validation.CandidateValidator;

public class CandidateMigrationService {

    private final SapClient sapClient;
    private final CandidateMapper mapper;
    private final CandidateValidator validator;
    private final SmartRecruitersClient smartRecruitersClient;

    public CandidateMigrationService(
            SapClient sapClient,
            CandidateMapper mapper,
            CandidateValidator validator,
            SmartRecruitersClient smartRecruitersClient
    ) {
        this.sapClient = sapClient;
        this.mapper = mapper;
        this.validator = validator;
        this.smartRecruitersClient = smartRecruitersClient;
    }
}