package org.wso2.carbon.identity.application.authenticator.handler.identifier;

import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.authentication.handler.identifier.UserResolveExecutor;
import org.wso2.carbon.identity.application.authentication.handler.identifier.internal.IdentifierAuthenticatorServiceComponent;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.flow.execution.engine.model.ExecutorResponse;
import org.wso2.carbon.identity.flow.execution.engine.model.FlowExecutionContext;
import org.wso2.carbon.identity.flow.execution.engine.model.FlowUser;
import org.wso2.carbon.identity.multi.attribute.login.mgt.MultiAttributeLoginService;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.claim.Claim;
import org.wso2.carbon.user.core.config.RealmConfiguration;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.tenant.TenantManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.wso2.carbon.identity.flow.execution.engine.Constants.ExecutorStatus.STATUS_COMPLETE;
import static org.wso2.carbon.identity.flow.execution.engine.Constants.ExecutorStatus.STATUS_ERROR;
import static org.wso2.carbon.identity.flow.execution.engine.Constants.ExecutorStatus.STATUS_USER_INPUT_REQUIRED;

public class UserResolveExecutorTestCase {

    private static final String TEST_USERNAME = "testUser";
    private static final String TEST_TENANT_DOMAIN = "carbon.super";
    private static final int TEST_TENANT_ID = 1;
    private static final String TEST_DOMAIN = "PRIMARY";
    private static final String TEST_DOMAIN_QUALIFIED_USERNAME = TEST_DOMAIN + UserCoreConstants.DOMAIN_SEPARATOR + TEST_USERNAME;

    @Mock
    private FlowExecutionContext mockContext;

    @Mock
    private FlowUser mockFlowUser;

    @Mock
    private RealmService mockRealmService;

    @Mock
    private TenantManager mockTenantManager;

    @Mock
    private UserRealm mockUserRealm;

    @Mock
    private UserStoreManager mockUserStoreManager;

    @Mock
    private UserStoreManager mockSecondaryUserStoreManager;

    @Mock
    private RealmConfiguration mockRealmConfiguration;

    @Mock
    private MultiAttributeLoginService mockMultiAttributeLoginService;

    private UserResolveExecutor userResolveExecutor;
    private final Map<String, Object> userClaims = new HashMap<>();
    private AutoCloseable closeable;
    private MockedStatic<IdentityUtil> identityUtilMock;

    @BeforeMethod
    public void setUp() throws Exception {

        closeable = MockitoAnnotations.openMocks(this);
        userResolveExecutor = new UserResolveExecutor();

        // Mock IdentityUtil to return empty list for excluded user stores
        identityUtilMock = mockStatic(IdentityUtil.class);
        identityUtilMock.when(() -> IdentityUtil.getPropertyAsList(UserResolveExecutor.FLOW_EXECUTION_USER_STORE_DOMAIN))
                .thenReturn(new ArrayList<>());

        // Always disable multi-attribute login in all tests
        when(mockMultiAttributeLoginService.isEnabled(anyString())).thenReturn(false);
        setMockMultiAttributeLoginService();

        // Mock context setup
        when(mockContext.getFlowUser()).thenReturn(mockFlowUser);
        when(mockContext.getTenantDomain()).thenReturn(TEST_TENANT_DOMAIN);
        when(mockFlowUser.getClaim(FrameworkConstants.USERNAME_CLAIM)).thenReturn(userClaims.get(FrameworkConstants.USERNAME_CLAIM));

        // Mock realm service setup
        setMockRealmService();

        // Mock user realm setup
        when(mockRealmService.getTenantManager()).thenReturn(mockTenantManager);
        when(mockTenantManager.getTenantId(TEST_TENANT_DOMAIN)).thenReturn(TEST_TENANT_ID);
        when(mockRealmService.getTenantUserRealm(TEST_TENANT_ID)).thenReturn(mockUserRealm);
        when(mockUserRealm.getUserStoreManager()).thenReturn(mockUserStoreManager);

        // Mock user store manager setup
        when(mockUserStoreManager.getRealmConfiguration()).thenReturn(mockRealmConfiguration);
        when(mockRealmConfiguration.getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME))
                .thenReturn("PRIMARY");
        when(mockUserStoreManager.getSecondaryUserStoreManager()).thenReturn(mockSecondaryUserStoreManager);
        when(mockSecondaryUserStoreManager.getRealmConfiguration()).thenReturn(mockRealmConfiguration);
        when(mockRealmConfiguration.getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME))
                .thenReturn(TEST_DOMAIN);
    }

    @AfterMethod
    public void tearDown() throws Exception {

        userClaims.clear();
        if (identityUtilMock != null) {
            identityUtilMock.close();
        }
        closeable.close();
        resetMockRealmService();
        resetMockMultiAttributeLoginService();
    }

    @Test
    public void testGetName() {

        Assert.assertEquals(userResolveExecutor.getName(), UserResolveExecutor.USER_RESOLVE_EXECUTOR);
    }

    @Test
    public void testExecuteWithNullUsername() throws Exception {

        userClaims.put(FrameworkConstants.USERNAME_CLAIM, null);
        ExecutorResponse response = userResolveExecutor.execute(mockContext);
        Assert.assertEquals(response.getResult(), STATUS_USER_INPUT_REQUIRED);
        verify(mockUserStoreManager, never()).getUserClaimValues(anyString(), any());
    }

    @Test
    public void testExecuteWithValidUsername() throws Exception {

        userClaims.put(FrameworkConstants.USERNAME_CLAIM, TEST_USERNAME);
        when(mockFlowUser.getClaim(FrameworkConstants.USERNAME_CLAIM)).thenReturn(TEST_USERNAME);
        when(mockUserStoreManager.isExistingUser(TEST_USERNAME)).thenReturn(false);
        when(mockUserStoreManager.isExistingUser("PRIMARY" + UserCoreConstants.DOMAIN_SEPARATOR + TEST_USERNAME)).thenReturn(true);

        Claim emailClaim = new Claim();
        emailClaim.setClaimUri("http://wso2.org/claims/emailaddress");
        emailClaim.setValue("test@example.com");

        Claim nameClaim = new Claim();
        nameClaim.setClaimUri("http://wso2.org/claims/givenname");
        nameClaim.setValue("Test User");

        Claim[] claims = new Claim[]{emailClaim, nameClaim};

        when(mockUserStoreManager.getUserClaimValues("PRIMARY" + UserCoreConstants.DOMAIN_SEPARATOR + TEST_USERNAME, null)).thenReturn(claims);

        ExecutorResponse response = userResolveExecutor.execute(mockContext);

        Assert.assertEquals(response.getResult(), STATUS_COMPLETE);
        verify(mockFlowUser).addClaims(any());
    }

    @Test
    public void testExecuteWithQualifiedUsername() throws Exception {

        String qualifiedUsername = TEST_DOMAIN_QUALIFIED_USERNAME;
        userClaims.put(FrameworkConstants.USERNAME_CLAIM, qualifiedUsername);

        when(mockFlowUser.getClaim(FrameworkConstants.USERNAME_CLAIM)).thenReturn(qualifiedUsername);
        when(mockUserStoreManager.isExistingUser(qualifiedUsername)).thenReturn(false);
        when(mockUserStoreManager.isExistingUser("PRIMARY" + UserCoreConstants.DOMAIN_SEPARATOR + qualifiedUsername)).thenReturn(true);
        when(mockUserStoreManager.getUserClaimValues("PRIMARY" + UserCoreConstants.DOMAIN_SEPARATOR + qualifiedUsername, null)).thenReturn(new Claim[0]);

        ExecutorResponse response = userResolveExecutor.execute(mockContext);

        Assert.assertEquals(response.getResult(), STATUS_COMPLETE);
    }

    @Test
    public void testResolveQualifiedUsernameInSecondaryUserStore() throws Exception {

        userClaims.put(FrameworkConstants.USERNAME_CLAIM, TEST_USERNAME);
        when(mockFlowUser.getClaim(FrameworkConstants.USERNAME_CLAIM)).thenReturn(TEST_USERNAME);
        when(mockUserStoreManager.isExistingUser(TEST_USERNAME)).thenReturn(false);
        when(mockUserStoreManager.isExistingUser("PRIMARY" + UserCoreConstants.DOMAIN_SEPARATOR + TEST_USERNAME)).thenReturn(false);
        when(mockUserStoreManager.getSecondaryUserStoreManager()).thenReturn(null); // No more secondary stores
        when(mockUserStoreManager.getUserClaimValues(TEST_USERNAME, null)).thenReturn(new Claim[0]);

        ExecutorResponse response = userResolveExecutor.execute(mockContext);

        Assert.assertEquals(response.getResult(), STATUS_COMPLETE);
    }

    @Test
    public void testUserStoreException() throws Exception {

        userClaims.put(FrameworkConstants.USERNAME_CLAIM, TEST_USERNAME);
        when(mockFlowUser.getClaim(FrameworkConstants.USERNAME_CLAIM)).thenReturn(TEST_USERNAME);
        when(mockUserStoreManager.isExistingUser(anyString())).thenThrow(new UserStoreException("Test exception"));

        ExecutorResponse response = userResolveExecutor.execute(mockContext);

        Assert.assertEquals(response.getResult(), STATUS_ERROR);
    }

    @Test
    public void testNullUserRealm() throws Exception {

        userClaims.put(FrameworkConstants.USERNAME_CLAIM, TEST_USERNAME);
        when(mockFlowUser.getClaim(FrameworkConstants.USERNAME_CLAIM)).thenReturn(TEST_USERNAME);
        when(mockRealmService.getTenantUserRealm(TEST_TENANT_ID)).thenReturn(null);

        ExecutorResponse response = userResolveExecutor.execute(mockContext);

        Assert.assertEquals(response.getResult(), STATUS_ERROR);
    }

    @Test
    public void testUserNotFound() throws Exception {

        userClaims.put(FrameworkConstants.USERNAME_CLAIM, TEST_USERNAME);
        when(mockFlowUser.getClaim(FrameworkConstants.USERNAME_CLAIM)).thenReturn(TEST_USERNAME);
        when(mockUserStoreManager.isExistingUser(anyString()))
                .thenThrow(new UserStoreException("30007 - User does not exist"));

        ExecutorResponse response = userResolveExecutor.execute(mockContext);

        Assert.assertEquals(response.getResult(), STATUS_COMPLETE);
    }

    @Test
    public void testUserStoreExceptionWithErrorCode() throws Exception {

        userClaims.put(FrameworkConstants.USERNAME_CLAIM, TEST_USERNAME);
        when(mockFlowUser.getClaim(FrameworkConstants.USERNAME_CLAIM)).thenReturn(TEST_USERNAME);
        when(mockUserStoreManager.isExistingUser(anyString()))
                .thenThrow(new UserStoreException("40001 - Some other error"));

        ExecutorResponse response = userResolveExecutor.execute(mockContext);

        Assert.assertEquals(response.getResult(), STATUS_ERROR);
        Assert.assertNotNull(response.getErrorMessage());
        Assert.assertTrue(response.getErrorMessage().contains("Error while resolving user"));
    }

    @Test
    public void testRollback() {

        ExecutorResponse response = userResolveExecutor.rollback(mockContext);
        Assert.assertNull(response);
    }

    private void setMockRealmService() throws Exception {

        Field field = IdentifierAuthenticatorServiceComponent.class.getDeclaredField("realmService");
        field.setAccessible(true);
        field.set(null, mockRealmService);
    }

    private void resetMockRealmService() throws Exception {

        Field field = IdentifierAuthenticatorServiceComponent.class.getDeclaredField("realmService");
        field.setAccessible(true);
        field.set(null, null);
    }

    private void setMockMultiAttributeLoginService() throws Exception {

        Field field = IdentifierAuthenticatorServiceComponent.class.getDeclaredField("multiAttributeLogin");
        field.setAccessible(true);
        field.set(null, mockMultiAttributeLoginService);
    }

    private void resetMockMultiAttributeLoginService() throws Exception {

        Field field = IdentifierAuthenticatorServiceComponent.class.getDeclaredField("multiAttributeLogin");
        field.setAccessible(true);
        field.set(null, null);
    }
}
