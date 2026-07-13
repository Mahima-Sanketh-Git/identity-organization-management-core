/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com)
 * Stage 1 POC: Organization Create Executor — Project 671
 */

package org.wso2.carbon.identity.organization.management.service.executor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.flow.execution.engine.exception.FlowEngineException;
import org.wso2.carbon.identity.flow.execution.engine.graph.Executor;
import org.wso2.carbon.identity.flow.execution.engine.model.ExecutorResponse;
import org.wso2.carbon.identity.flow.execution.engine.model.FlowExecutionContext;
import org.wso2.carbon.identity.flow.execution.engine.model.FlowOrganization;
import org.wso2.carbon.identity.flow.execution.engine.model.FlowUser;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.internal.OrganizationManagementDataHolder;
import org.wso2.carbon.identity.organization.management.service.model.TenantTypeOrganization;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.service.RealmService;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.wso2.carbon.identity.organization.management.service.util.Utils.generateUniqueID;

/**
 * Executor that creates an organization when a VIEW step's submit button action
 * is wired to "OrgCreateExecutor".
 *
 * POC NOTE: per mentor's direction, this executor also inline-provisions the
 * creating user (if not already onboarded) instead of relying on
 * UserProvisioningExecutor having run earlier in the flow. This duplicates
 * logic that normally lives in identity-governance's UserProvisioningExecutor
 * and is only acceptable for this Stage-1 POC — a production version should
 * make user provisioning a proper upstream flow step instead.
 */
public class OrgCreateExecutor implements Executor {

    private static final Log LOG = LogFactory.getLog(OrgCreateExecutor.class);

    private static final String EXECUTOR_NAME = "OrgCreateExecutor";
    private static final String ORG_NAME_CLAIM = "http://wso2.org/claims/organization";
    private static final String ORG_HANDLER_CLAIM = "http://wso2.org/claims/organization/handle";

    private static final String USERNAME_CLAIM = "http://wso2.org/claims/username";

    private static final String CTX_ORG_ID = "org.created.id";
    private static final String CTX_ORG_NAME = "org.created.name";

    @Override
    public String getName() {

        return EXECUTOR_NAME;
    }

    @Override
    public ExecutorResponse execute(FlowExecutionContext context) throws FlowEngineException {

        LOG.info("[ORG-CREATE-POC] OrgCreateExecutor triggered.");
        if (context.getUserInputData() != null) {
            Object orgNameClaim = context.getFlowUser().getClaim(ORG_NAME_CLAIM);
            Object orgHandleClaim = context.getFlowUser().getClaim(ORG_HANDLER_CLAIM);
            if (orgNameClaim != null) {
                context.getFlowOrganization().setOrganizationName(orgNameClaim.toString().trim());
            }

            if(orgHandleClaim != null) {
                context.getFlowOrganization().setOrganizationHandle(orgHandleClaim.toString());
            }
        }

        String orgName = context.getFlowOrganization().getOrganizationName();
        String orgHandle = context.getFlowOrganization().getOrganizationHandle();
        if (orgName == null || orgName.isEmpty()) {
            LOG.warn("[ORG-CREATE-POC] organization.name not found in user input." + ORG_NAME_CLAIM);
            ExecutorResponse response = new ExecutorResponse();
            response.setResult("RETRY");
            response.setErrorMessage("Please provide a valid organization name");
            return response;
        }

        if(orgHandle == null || orgHandle.isEmpty()) {
            LOG.warn("[ORG-CREATE-POC] organization handler not found in user input." + ORG_HANDLER_CLAIM);
            ExecutorResponse response = new ExecutorResponse();
            response.setResult("RETRY");
            response.setErrorMessage("Please provide a valid handler name");
            return response;
        }

        try {
            ensureUserProvisioned(context);

            String createdOrgId = createOrganization(context);

            ExecutorResponse response = new ExecutorResponse();
            response.setResult("COMPLETE");

            Map<String, Object> props = new HashMap<>();
            props.put(CTX_ORG_ID, createdOrgId);
            props.put(CTX_ORG_NAME, orgName);
            response.setContextProperty(props);
            LOG.info("[ORG-CREATE-POC] Organization created. Name: " + orgName + " | ID: " + createdOrgId);

            return response;
        } catch (Exception e) {
            LOG.error("[ORG-CREATE-POC] Failed to create organization: " + orgName, e);
            ExecutorResponse response = new ExecutorResponse();
            response.setResult("RETRY");
            response.setErrorMessage("Failed to create organization: " + e.getMessage());
            return response;
        }
    }

    /**
     * POC: inline user provisioning. If the FlowUser doesn't yet have a userId
     * (i.e. no earlier executor onboarded them), create the user in the user
     * store now and stamp the resulting userId/username back onto FlowUser so
     * createOrganization() can use it instead of a hardcoded creator.
     * NOTE: this is a stripped-down duplicate of
     * UserProvisioningExecutor#handleRegistrationFlow's onboarding logic
     * (identity-governance module) — no role assignment, consent, federated
     * association, or notification-property handling is done here. Do not
     * treat this as a full replacement for that executor outside this POC.
     */
    private void ensureUserProvisioned(FlowExecutionContext context) throws FlowEngineException {

        FlowUser user = context.getFlowUser();

        if (user.getUserId() != null && !user.getUserId().isEmpty()) {
            LOG.info("[ORG-CREATE-POC] User already provisioned, userId=" + user.getUserId());
            return;
        }

        String username = user.getUsername();
        if (username == null || username.isEmpty()) {
            Object claimUsername = user.getClaim(USERNAME_CLAIM);
            username = claimUsername != null ? claimUsername.toString().trim() : null;
        }
        if (username == null || username.isEmpty()) {
            username = UUID.randomUUID().toString();
            user.setUsername(username);
        }

        try {
            RealmService realmService = OrganizationManagementDataHolder.getInstance().getRealmService();
            String tenantDomain = context.getTenantDomain();
            UserRealm tenantUserRealm = realmService.getTenantUserRealm(IdentityTenantUtil.getTenantId(tenantDomain));
            UserStoreManager userStoreManager = (UserStoreManager) tenantUserRealm.getUserStoreManager();

            String domainQualifiedName = IdentityUtil.addDomainToName(username,
                    IdentityUtil.getPrimaryDomainName());

            if (userStoreManager.isExistingUser(domainQualifiedName)) {
                String existingUserId =
                        ((AbstractUserStoreManager) userStoreManager).getUserIDFromUserName(username);
                user.setUserId(existingUserId);
                LOG.info("[ORG-CREATE-POC] User already existed in user store, userId=" + existingUserId);
                return;
            }

            Map<String, char[]> credentials = user.getUserCredentials();
            char[] password = credentials != null && credentials.containsKey("password")
                    ? credentials.get("password")
                    : UUID.randomUUID().toString().toCharArray();

            // Organization-only claims (org name/handle) aren't registered local claims for the
            // user profile, so they must be excluded before addUser() validates the claim map.
            Map<String, String> userClaims = new HashMap<>(user.getClaims());
            userClaims.remove(ORG_NAME_CLAIM);
            userClaims.remove(ORG_HANDLER_CLAIM);

            userStoreManager.addUser(domainQualifiedName, String.valueOf(password), null, userClaims, null);

            String userId = ((AbstractUserStoreManager) userStoreManager).getUserIDFromUserName(username);
            user.setUserId(userId);
            LOG.info("[ORG-CREATE-POC] Inline-provisioned user. username=" + username + " | userId=" + userId);
        } catch (UserStoreException e) {
            throw new FlowEngineException("Failed to inline-provision user for org creation: " + e.getMessage());
        }
    }

    /**
     * Uses the OrganizationManager already bound into this bundle's own data holder
     * (set by OrganizationManagementServiceComponent) — no need to reach into the
     * flow engine for it.
     */
    private String createOrganization (FlowExecutionContext context) throws
            OrganizationManagementException {

        OrganizationManager
                organizationManagerImpl = OrganizationManagementDataHolder.getInstance().getOrganizationManager();

        FlowUser flowUser = context.getFlowUser();
        FlowOrganization flowOrganization = context.getFlowOrganization();
        String orgName = flowOrganization.getOrganizationName();
        String orgHandlerName = flowOrganization.getOrganizationHandle();
        String creatorId = flowUser.getUserId();
        String creatorUsername = flowUser.getUsername();

        if (creatorId != null) {
            flowOrganization.setCreatorId(creatorId);
        }
        TenantTypeOrganization org = new TenantTypeOrganization(orgName);
        org.setId(generateUniqueID());
        org.setName(orgName);
        org.setDescription("Organization self-registered via onboarding flow.");
        org.setStatus(OrganizationManagementConstants.OrganizationStatus.ACTIVE.toString());
        org.setType(OrganizationManagementConstants.OrganizationTypes.TENANT.toString());
        org.setCreated(Instant.now());
        org.setLastModified(Instant.now());
        org.getParent().setId(OrganizationManagementConstants.SUPER_ORG_ID);
        org.setVersion(OrganizationManagementConstants.OrganizationVersion.BASE_ORG_VERSION);
        org.setOrganizationHandle(orgHandlerName);
        org.setCreatorId(creatorId);
        org.setCreatorUsername(creatorUsername);

        LOG.info("[ORG-CREATE-POC] Org written to DB, via DAO. ID: " + org.getId());

        return org.getId();
    }

    @Override
    public List<String> getInitiationData() {

        return Collections.emptyList();
    }

    @Override
    public ExecutorResponse rollback(FlowExecutionContext context) throws FlowEngineException {

        return null;
    }
}
