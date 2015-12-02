package org.sagebionetworks.bridge.sdk.integration;

import static org.junit.Assert.assertEquals;

import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.sagebionetworks.bridge.IntegrationSmokeTest;
import org.sagebionetworks.bridge.sdk.ClientProvider;
import org.sagebionetworks.bridge.sdk.DeveloperClient;
import org.sagebionetworks.bridge.sdk.Roles;
import org.sagebionetworks.bridge.sdk.Session;
import org.sagebionetworks.bridge.sdk.TestUserHelper;
import org.sagebionetworks.bridge.sdk.TestUserHelper.TestUser;
import org.sagebionetworks.bridge.sdk.UserClient;
import org.sagebionetworks.bridge.sdk.models.studies.Study;
import org.sagebionetworks.bridge.sdk.models.users.DataGroups;

import com.google.common.collect.Sets;

@Category(IntegrationSmokeTest.class)
public class SignInTest {

    private TestUser developer;
    private TestUser user;
    
    @Before
    public void before() {
        developer = TestUserHelper.createAndSignInUser(SignInTest.class, true, Roles.DEVELOPER);
        user = TestUserHelper.createAndSignInUser(SignInTest.class, true);
        
        DeveloperClient devClient = developer.getSession().getDeveloperClient();
        Study study = devClient.getStudy();
        Set<String> taskIdentifiers = study.getTaskIdentifiers();
        if (!taskIdentifiers.contains("sdk-int-1")) {
            taskIdentifiers.add("sdk-int-1");
            devClient.updateStudy(study);
        }
    }
    
    @After
    public void after() {
        user.signOutAndDeleteUser();
        developer.signOutAndDeleteUser();
    }

    @Test
    public void canGetDataGroups(){
        UserClient client = user.getSession().getUserClient();
        
        DataGroups dataGroups = new DataGroups();
        dataGroups.setDataGroups(Sets.newHashSet("sdk-int-1"));
        client.updateDataGroups(dataGroups);
        
        user.getSession().signOut();
        
        Session session = ClientProvider.signIn(user.getSignInCredentials());
        DataGroups groups = session.getDataGroups();
        assertEquals(Sets.newHashSet("sdk-int-1"), groups.getDataGroups());
    }
    
}