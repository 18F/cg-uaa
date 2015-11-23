package org.cloudfoundry.identity.uaa.invitations;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.AbstractIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthenticationDetails;
import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.authentication.manager.DynamicZoneAwareAuthenticationManager;
import org.cloudfoundry.identity.uaa.client.ClientConstants;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCode;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCodeStore;
import org.cloudfoundry.identity.uaa.invitations.InvitationsService.AcceptedInvitation;
import org.cloudfoundry.identity.uaa.ldap.LdapIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.login.PasswordConfirmationValidation;
import org.cloudfoundry.identity.uaa.login.saml.SamlIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.login.saml.SamlRedirectUtils;
import org.cloudfoundry.identity.uaa.scim.exception.InvalidPasswordException;
import org.cloudfoundry.identity.uaa.scim.validate.PasswordValidator;
import org.cloudfoundry.identity.uaa.user.UaaAuthority;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.cloudfoundry.identity.uaa.user.UaaUserDatabase;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityProvider;
import org.cloudfoundry.identity.uaa.zone.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.UaaIdentityProviderDefinition;
import org.hibernate.validator.constraints.Email;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.ClientDetailsService;
import org.springframework.security.oauth2.provider.NoSuchClientException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Collections.EMPTY_LIST;
import static org.cloudfoundry.identity.uaa.authentication.Origin.ORIGIN;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;


@Controller
@RequestMapping("/invitations")
public class InvitationsController {

    private static Log logger = LogFactory.getLog(InvitationsController.class);

    private final InvitationsService invitationsService;

    private PasswordValidator passwordValidator;
    private ExpiringCodeStore expiringCodeStore;
    private IdentityProviderProvisioning providerProvisioning;
    private UaaUserDatabase userDatabase;

    public void setExpiringCodeStore(ExpiringCodeStore expiringCodeStore) {
        this.expiringCodeStore = expiringCodeStore;
    }

    public void setPasswordValidator(PasswordValidator passwordValidator) {
        this.passwordValidator = passwordValidator;
    }

    public void setProviderProvisioning(IdentityProviderProvisioning providerProvisioning) {
        this.providerProvisioning = providerProvisioning;
    }

    public void setUserDatabase(UaaUserDatabase userDatabase) {
        this.userDatabase = userDatabase;
    }

    private String spEntityID;

    public InvitationsController(InvitationsService invitationsService) {
        this.invitationsService = invitationsService;
    }

    public String getSpEntityID() {
        return spEntityID;
    }

    public void setSpEntityID(String spEntityID) {
        this.spEntityID = spEntityID;
    }

    @RequestMapping(value = {"/sent", "/new", "/new.do"})
    public void return404(HttpServletResponse response) {
        response.setStatus(404);
    }

    @RequestMapping(value = "/accept", method = GET, params = {"code"})
    public String acceptInvitePage(@RequestParam String code, Model model, HttpServletRequest request, HttpServletResponse response) throws IOException {

        ExpiringCode expiringCode = expiringCodeStore.retrieveCode(code);
        if (expiringCode==null) {
            return handleUnprocessableEntity(model, response, "error_message_code", "code_expired", "invitations/accept_invite");
        }

        Map<String, String> codeData = JsonUtils.readValue(expiringCode.getData(), new TypeReference<Map<String, String>>() {});
        String origin = codeData.get(ORIGIN);
        try {
            IdentityProvider provider = providerProvisioning.retrieveByOrigin(origin, IdentityZoneHolder.get().getId());
            String newCode = expiringCodeStore.generateCode(expiringCode.getData(), new Timestamp(System.currentTimeMillis() + (10 * 60 * 1000))).getCode();
            UaaUser user = userDatabase.retrieveUserById(codeData.get("user_id"));
            if (user.isVerified() || Origin.SAML.equals(provider.getType()) || Origin.LDAP.equals(provider.getType())) {
                AcceptedInvitation accepted = invitationsService.acceptInvitation(newCode, "");
                String redirect = "redirect:" + accepted.getRedirectUri();
                logger.debug(String.format("Redirecting accepted invitation for email:%s, id:%s to URL:%s", codeData.get("email"), codeData.get("user_id"), redirect));
                return redirect;
            } else {
                UaaPrincipal uaaPrincipal = new UaaPrincipal(codeData.get("user_id"), codeData.get("email"), codeData.get("email"), origin, null, IdentityZoneHolder.get().getId());
                AnonymousAuthenticationToken token = new AnonymousAuthenticationToken("scim.invite", uaaPrincipal, Arrays.asList(UaaAuthority.UAA_INVITED));
                SecurityContextHolder.getContext().setAuthentication(token);
                model.addAttribute(Origin.UAA, Arrays.asList(provider));
                model.addAttribute("code", newCode);
                model.addAttribute("passwordPolicy", passwordValidator.getPasswordPolicy());
                logger.debug(String.format("Sending user to accept invitation page email:%s, id:%s", codeData.get("email"), codeData.get("user_id")));
            }
            model.addAllAttributes(codeData);
            return "invitations/accept_invite";
        } catch (EmptyResultDataAccessException noProviderFound) {
            logger.debug(String.format("No available invitation providers for email:%s, id:%s", codeData.get("email"), codeData.get("user_id")));
            return handleUnprocessableEntity(model, response, "error_message_code", "no_suitable_idp", "invitations/accept_invite");
        }
    }

    @RequestMapping(value = "/accept.do", method = POST)
    public String acceptInvitation(@RequestParam("password") String password,
                                   @RequestParam("password_confirmation") String passwordConfirmation,
                                   @RequestParam("code") String code,
                                   @RequestParam(value = "client_id", required = false, defaultValue = "") String clientId,
                                   @RequestParam(value = "redirect_uri", required = false, defaultValue = "") String redirectUri,
                                   Model model,
                                   HttpServletRequest request,
                                   HttpServletResponse response) throws IOException {

        PasswordConfirmationValidation validation = new PasswordConfirmationValidation(password, passwordConfirmation);

        UaaPrincipal principal =  (UaaPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        if (!validation.valid()) {
            model.addAttribute("email", principal.getEmail());
            return handleUnprocessableEntity(model, response, "error_message_code", validation.getMessageCode(), "invitations/accept_invite");
        }
        try {
            passwordValidator.validate(password);
        } catch (InvalidPasswordException e) {
            model.addAttribute("email", principal.getEmail());
            return handleUnprocessableEntity(model, response, "error_message", e.getMessagesAsOneString(), "invitations/accept_invite");
        }
        AcceptedInvitation invitation = invitationsService.acceptInvitation(code, password);
        principal = new UaaPrincipal(
            invitation.getUser().getId(),
            invitation.getUser().getUserName(),
            invitation.getUser().getPrimaryEmail(),
            invitation.getUser().getOrigin(),
            invitation.getUser().getExternalId(),
            IdentityZoneHolder.get().getId()
        );
        UaaAuthentication authentication = new UaaAuthentication(principal, UaaAuthority.USER_AUTHORITIES, new UaaAuthenticationDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return "redirect:" + invitation.getRedirectUri();
    }

    private String handleUnprocessableEntity(Model model, HttpServletResponse response, String attributeKey, String attributeValue, String view) {
        model.addAttribute(attributeKey, attributeValue);
        response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        return view;
    }

    public static class ValidEmail {
        @Email
        String email;

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }
}
