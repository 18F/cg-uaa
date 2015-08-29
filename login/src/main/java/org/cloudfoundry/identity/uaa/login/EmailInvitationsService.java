package org.cloudfoundry.identity.uaa.login;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.error.UaaException;
import org.cloudfoundry.identity.uaa.login.AccountCreationService.ExistingUserResponse;
import org.cloudfoundry.identity.uaa.message.PasswordChangeRequest;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.ScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.exception.ScimResourceAlreadyExistsException;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring4.SpringTemplateEngine;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class EmailInvitationsService implements InvitationsService {
    private final Log logger = LogFactory.getLog(getClass());

    public static final int INVITATION_EXPIRY_DAYS = 365;

    private final SpringTemplateEngine templateEngine;
    private final MessageService messageService;

    @Autowired
    private ScimUserProvisioning scimUserProvisioning;


    private String brand;

    public EmailInvitationsService(SpringTemplateEngine templateEngine, MessageService messageService, String brand) {
        this.templateEngine = templateEngine;
        this.messageService = messageService;
        this.brand = brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    @Autowired
    private AccountCreationService accountCreationService;

    @Autowired
    private ExpiringCodeService expiringCodeService;

    private void sendInvitationEmail(String email, String currentUser, String code) {
        String subject = getSubjectText();
        try {
            String htmlContent = getEmailHtml(currentUser, code);
            messageService.sendMessage(email, MessageType.INVITATION, subject, htmlContent);
        } catch (RestClientException e) {
            logger.info("Exception raised while creating invitation email from " + email, e);
        }
    }

    private String getSubjectText() {
        return brand.equals("pivotal") ? "Invitation to join Pivotal" : "Invitation to join Cloud Foundry";
    }

    private String getEmailHtml(String currentUser, String code) {
        String accountsUrl = ServletUriComponentsBuilder.fromCurrentContextPath().path("/invitations/accept").build().toUriString();
        final Context ctx = new Context();
        ctx.setVariable("serviceName", brand.equals("pivotal") ? "Pivotal" : "Cloud Foundry");
        ctx.setVariable("code", code);
        ctx.setVariable("currentUser", currentUser);
        ctx.setVariable("accountsUrl", accountsUrl);
        return templateEngine.process("invite", ctx);
    }

    @Override
    public void inviteUser(String email, String currentUser, String redirectUri) {
        try {
            ScimUser user = accountCreationService.createUser(email, new RandomValueStringGenerator().generate());
            Map<String,String> data = new HashMap<>();
            data.put("user_id", user.getId());
            data.put("email", email);
            data.put("redirect_uri", redirectUri);
            String code = expiringCodeService.generateCode(data, INVITATION_EXPIRY_DAYS, TimeUnit.DAYS);
            sendInvitationEmail(email, currentUser, code);
        } catch (ScimResourceAlreadyExistsException e) {
            try {
                ExistingUserResponse existingUserResponse = JsonUtils.convertValue(e.getExtraInfo(), ExistingUserResponse.class);
                if (existingUserResponse.getVerified()) {
                    throw new UaaException(e.getMessage(), e.getStatus().value());
                }
                Map<String,String> data = new HashMap<>();
                data.put("user_id", existingUserResponse.getUserId());
                data.put("email", email);
                data.put("redirect_uri", redirectUri);
                String code = expiringCodeService.generateCode(data, INVITATION_EXPIRY_DAYS, TimeUnit.DAYS);
                sendInvitationEmail(email, currentUser, code);
            } catch (JsonUtils.JsonUtilException ioe) {
                logger.warn("couldn't invite user",ioe);
            } catch (IOException ioe) {
                logger.warn("couldn't invite user",ioe);
            }
        } catch (IOException e) {
            logger.warn("couldn't invite user",e);
        }
    }

    @Override
    public void acceptInvitation(String userId, String email, String password) {
        ScimUser user = scimUserProvisioning.retrieve(userId);
        scimUserProvisioning.verifyUser(user.getId(), user.getVersion());

        PasswordChangeRequest request = new PasswordChangeRequest();
        request.setPassword(password);

        scimUserProvisioning.changePassword(userId, null, password);
    }
}
