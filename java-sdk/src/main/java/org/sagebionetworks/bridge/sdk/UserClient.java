package org.sagebionetworks.bridge.sdk;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.sagebionetworks.bridge.sdk.exceptions.BridgeSDKException;
import org.sagebionetworks.bridge.sdk.models.ResourceList;
import org.sagebionetworks.bridge.sdk.models.accounts.ConsentSignature;
import org.sagebionetworks.bridge.sdk.models.accounts.SharingScope;
import org.sagebionetworks.bridge.sdk.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.sdk.models.accounts.Withdrawal;
import org.sagebionetworks.bridge.sdk.models.holders.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.sdk.models.holders.IdentifierHolder;
import org.sagebionetworks.bridge.sdk.models.holders.SimpleIdentifierHolder;
import org.sagebionetworks.bridge.sdk.models.schedules.Schedule;
import org.sagebionetworks.bridge.sdk.models.schedules.ScheduledActivity;
import org.sagebionetworks.bridge.sdk.models.subpopulations.ConsentStatus;
import org.sagebionetworks.bridge.sdk.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.sdk.models.surveys.Survey;
import org.sagebionetworks.bridge.sdk.models.surveys.SurveyAnswer;
import org.sagebionetworks.bridge.sdk.models.surveys.SurveyResponse;
import org.sagebionetworks.bridge.sdk.models.upload.UploadRequest;
import org.sagebionetworks.bridge.sdk.models.upload.UploadSession;
import org.sagebionetworks.bridge.sdk.models.upload.UploadValidationStatus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;

public class UserClient extends BaseApiCaller {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormat.forPattern("ZZ");
    
    private final TypeReference<ResourceList<Schedule>> sType = new TypeReference<ResourceList<Schedule>>() {};
    
    private final TypeReference<ResourceList<ScheduledActivity>> saType = new TypeReference<ResourceList<ScheduledActivity>>() {};

    UserClient(BridgeSession session) {
        super(session);
    }

    /**
     * Get the StudyParticipant record for the currently authenticated user.
     * 
     * @return participant - the study participant record for this user
     */
    public StudyParticipant getStudyParticipant() {
        session.checkSignedIn();
        
        return get(config.getParticipantSelfApi(), StudyParticipant.class);
    }
    
    /**
     * Update the StudyParticipant associated with the currently authenticated user. 
     * Unlike other API calls, this call can take a sparse StudyParticipant object 
     * where only the fields you wish to update have been added to the object. Null 
     * fields will be ignored when updating the study participant's information (it 
     * will remain the same on the server).
     * 
     * @param participant
     *          The study participant object to update for this user; may be sparse 
     *          (only fill out fields you wish to update).
     */
    public void saveStudyParticipant(StudyParticipant participant) {
        session.checkSignedIn();
        checkNotNull(participant, "StudyParticipant cannot be null.");

        UserSession userSession = post(config.getParticipantSelfApi(), participant, UserSession.class);
        session.setUserSession(userSession);
    }
    
    /**
     * Consent to research.
     *
     * @param subpopGuid
     *          The subpopulation of the study that the user is consenting to participate int.
     * @param signature
     *          Name, birthdate, and optionally signature image, of consenter's signature.
     * @param scope
     *          Scope of sharing for this consent
     */
    public void consentToResearch(SubpopulationGuid subpopGuid, ConsentSignature signature, SharingScope scope) {
        session.checkSignedIn();
        checkNotNull(subpopGuid, CANNOT_BE_NULL, "subpopGuid");
        checkNotNull(signature, CANNOT_BE_NULL, "ConsentSignature");
        checkNotNull(scope, CANNOT_BE_NULL, "SharingScope");

        ConsentSubmission submission = new ConsentSubmission(signature, scope);
        
        post(config.getConsentSignatureApi(subpopGuid), submission);
        session.setSharingScope(scope);
        changeConsent(subpopGuid, true);
    }

    /**
     * Returns the user's consent signature, which includes the name, birthdate, and signature image.
     *
     * @param subpopGuid
     * @return consent signature
     */
    public ConsentSignature getConsentSignature(SubpopulationGuid subpopGuid) {
        session.checkSignedIn();
        checkNotNull(subpopGuid, CANNOT_BE_NULL, "subpopGuid");
        
        ConsentSignature sig = get(config.getConsentSignatureApi(subpopGuid), ConsentSignature.class);
        return sig;
    }

    /**
     * Email the signed consent agreement to the participant's email address.
     * 
     * @param subpopGuid
     */
    public void emailConsentSignature(SubpopulationGuid subpopGuid) {
        session.checkSignedIn();
        checkNotNull(subpopGuid, CANNOT_BE_NULL, "subpopGuid");
        
        post(config.getEmailConsentSignatureApi(subpopGuid));
    }

    /**
     * Withdraw user's consent to participate in research. The user will no longer be able to submit 
     * data to the server without receiving an error response from the server (ConsentRequiredException).
     * @param subpopGuid
     * @param reason
     */
    public void withdrawConsentToResearch(SubpopulationGuid subpopGuid, String reason) {
        session.checkSignedIn();
        checkNotNull(subpopGuid, CANNOT_BE_NULL, "subpopGuid");
        
        Withdrawal withdrawal = new Withdrawal(reason);
        post(config.getWithdrawConsentSignatureApi(subpopGuid), withdrawal);
        // alxdark (12/18/2015): TODO: implement the same logic as is on the server to determine
        // when we should do this? Right now this might not be accurate for multiple consents, 
        // although no studies exist in this configuration at this time.
        session.setSharingScope(SharingScope.NO_SHARING); 
        changeConsent(subpopGuid, false);
    }
    
    /**
     * Get all schedules associated with a study.
     *
     * @return List<Schedule>
     */
    public ResourceList<Schedule> getSchedules() {
        session.checkSignedIn();
        return get(config.getSchedulesApi(), sType);
    }

    /**
     * Get a survey version with a GUID and a createdOn timestamp.
     *
     * @param keys
     *
     * @return Survey
     */
    public Survey getSurvey(GuidCreatedOnVersionHolder keys) {
        session.checkSignedIn();
        checkNotNull(keys, CANNOT_BE_NULL, "guid/createdOn keys");

        return get(config.getSurveyApi(keys.getGuid(), keys.getCreatedOn()), Survey.class);
    }
    
    /**
     * Get the most recently published survey available for the provided survey GUID.
     * @param guid
     * 
     * @return Survey
     */
    public Survey getSurveyMostRecentlyPublished(String guid) {
        session.checkSignedIn();
        checkNotNull(guid, CANNOT_BE_NULL, "guid");
        
        return get(config.getRecentlyPublishedSurveyForUserApi(guid), Survey.class);
    }

    /**
     * Submit a list of SurveyAnswers to a particular survey. An identifier for the survey response will be 
     * auto-generated and returned by the server.
     *
     * @param keys
     *            The survey that the answers will be added to.
     * @param answers
     *            The answers to add to the survey.
     * @return GuidHolder A holder storing the GUID of the survey.
     */
    public IdentifierHolder submitAnswersToSurvey(GuidCreatedOnVersionHolder keys, List<SurveyAnswer> answers) {
        session.checkSignedIn();
        checkNotNull(keys, "Survey keys cannot be null.");
        checkNotNull(answers, "Answers cannot be null.");

        SurveyResponseSubmit response = new SurveyResponseSubmit(keys, null, answers);
        return post(config.getSurveyResponsesApi(), response, SimpleIdentifierHolder.class);
    }

    /**
     * Submit a list of SurveyAnswers to a particular survey, using a specified identifier
     * for the survey response (the value should be a unique string, like a GUID, that 
     * has not been used for any prior submissions).
     *
     * @param keys
     *            The survey that the answers will be added to.
     * @param identifier
     *            A unique string to identify this set of survey answers as originating
     *            from the same run of a survey
     * @param answers
     *            The answers to add to the survey.
     * @return IdentifierHolder A holder storing the GUID of the survey.
     */
    public IdentifierHolder submitAnswersToSurvey(GuidCreatedOnVersionHolder keys, String identifier, List<SurveyAnswer> answers) {
        session.checkSignedIn();
        checkNotNull(keys, "GuidCreatedOnVersionHolder cannot be null.");
        checkNotNull(identifier, "identifier cannot be null.");
        checkNotNull(answers, "Answers cannot be null.");

        SurveyResponseSubmit response = new SurveyResponseSubmit(keys, identifier, answers);
        return post(config.getSurveyResponsesApi(), response, SimpleIdentifierHolder.class);
    }
    
    /**
     * Get the survey response associated with the identifier string.
     *
     * @param identifier
     *            The identifier for this SurveyResponse
     * @return SurveyResponse
     */
    public SurveyResponse getSurveyResponse(String identifier) {
        session.checkSignedIn();
        checkArgument(isNotBlank(identifier), "Survey response identifier cannot be null or empty.");

        return get(config.getSurveyResponseApi(identifier), SurveyResponse.class);
    }

    /**
     * Add a list of SurveyAnswers to a SurveyResponse.
     *
     * @param identifier
     *            The identifier for the response that answers will be added (the response must already exist).
     * @param answers
     *            The answers that will be added to the response.
     */
    public void addAnswersToResponse(String identifier, List<SurveyAnswer> answers) {
        session.checkSignedIn();
        checkArgument(isNotBlank(identifier), "Identifier cannot be null or empty.");
        checkNotNull(answers, "Answers cannot be null.");
        
        SurveyResponseSubmit res = new SurveyResponseSubmit(null, identifier, answers);
        post(config.getSurveyResponseApi(identifier), res);
    }

    /**
     * Request an upload session from the user.
     *
     * @param request
     *            the request object Bridge uses to create the Upload Session.
     * @return UploadSession
     */
    public UploadSession requestUploadSession(UploadRequest request) {
        session.checkSignedIn();
        checkNotNull(request, "Request cannot be null.");

        return post(config.getUploadsApi(), request, UploadSession.class);
    }

    /**
     * Upload a file using the requested UploadSession. Closes the upload after it's done.
     *
     * @param session
     *            The session used to upload.
     * @param fileName
     *            File to upload.
     */
    public void upload(UploadSession session, UploadRequest request, String fileName) {
        this.session.checkSignedIn();
        checkNotNull(session, "session cannot be null.");
        checkNotNull(fileName, "fileName cannot be null.");
        checkArgument(session.getExpires().isAfter(DateTime.now()), "session already expired, cannot upload.");

        HttpEntity entity = null;
        try {
            byte[] b = Files.readAllBytes(Paths.get(fileName));
            entity = new ByteArrayEntity(b, ContentType.create(request.getContentType()));
        } catch (FileNotFoundException e) {
            throw new BridgeSDKException(e.getMessage(), e, config.getCompleteUploadApi(session.getId()));
        } catch (IOException e) {
            throw new BridgeSDKException(e.getMessage(), e, config.getCompleteUploadApi(session.getId()));
        }
        String url = session.getUrl().toString();
        s3Put(url, entity, request);
        
        post(config.getCompleteUploadApi(session.getId()));
    }

    /**
     * Gets the upload status (status and validation messages) for the given upload ID
     *
     * @param uploadId
     *         ID of the upload, obtained from the upload session
     * @return object containing upload status and validation messages
     */
    public UploadValidationStatus getUploadStatus(String uploadId) {
        session.checkSignedIn();
        checkArgument(isNotBlank(uploadId), CANNOT_BE_BLANK, "uploadId");
        return get(config.getUploadStatusApi(uploadId), UploadValidationStatus.class);
    }

    /**
     * Get the list of available or scheduled activities.
     * @param daysAhead
     *      return activities from now until the number of days ahead from now (maximum of 4 days)
     * @param timeZone
     *      the timezone the activities should use when returning scheduledOn and expiresOn dates
     * @return
     */
    public ResourceList<ScheduledActivity> getScheduledActivities(int daysAhead, DateTimeZone timeZone) {
        session.checkSignedIn();
        try {
            String offsetString = DATETIME_FORMATTER.withZone(timeZone).print(0);
            String queryString = "?daysAhead=" + Integer.toString(daysAhead) + "&offset="
                            + URLEncoder.encode(offsetString, "UTF-8");
            return get(config.getScheduledActivitiesApi() + queryString, saType);
        } catch(UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Update these activities (by setting either the startedOn or finishedOn values of each activity). 
     * The only other required value that must be set for the activity is its GUID.
     * @param scheduledActivities
     */
    public void updateScheduledActivities(List<ScheduledActivity> scheduledActivities) {
        checkNotNull(scheduledActivities);
        session.checkSignedIn();
        post(config.getScheduledActivitiesApi(), scheduledActivities);
    }
    
    private void changeConsent(SubpopulationGuid subpopGuid, boolean isConsented) {
        ImmutableMap.Builder<SubpopulationGuid,ConsentStatus> builder = new ImmutableMap.Builder<>();
        for (Map.Entry<SubpopulationGuid, ConsentStatus> entry : session.getConsentStatuses().entrySet()) {
            SubpopulationGuid guid = entry.getKey();
            ConsentStatus status = entry.getValue();
            if (status.getSubpopulationGuid().equals(subpopGuid.getGuid())) {
                builder.put(guid, new ConsentStatus(status.getName(), status.getSubpopulationGuid(), 
                        status.isRequired(), isConsented, status.isMostRecentConsent()));
            } else {
                builder.put(guid, status);
            }
        }
        session.setConsentStatuses(builder.build());
    }
    
}