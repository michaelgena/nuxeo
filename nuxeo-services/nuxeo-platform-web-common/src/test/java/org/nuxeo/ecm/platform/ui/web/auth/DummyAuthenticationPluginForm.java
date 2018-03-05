/*
 * (C) Copyright 2018 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.platform.ui.web.auth;

import static java.lang.Boolean.FALSE;
import static org.apache.commons.lang3.StringUtils.isBlank;

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.nuxeo.ecm.platform.api.login.UserIdentificationInfo;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.NuxeoAuthenticationPlugin;
import org.nuxeo.ecm.platform.ui.web.auth.interfaces.NuxeoAuthenticationPluginLogoutExtension;

/**
 * Dummy authentication plugin that identifies everyone as {@code DummyAnonyous}.
 *
 * @since 10.1
 */
public class DummyAuthenticationPluginForm
        implements NuxeoAuthenticationPlugin, NuxeoAuthenticationPluginLogoutExtension {

    public static final String DUMMY_AUTH_FORM_USERNAME_KEY = "username";

    public static final String DUMMY_AUTH_FORM_PASSWORD_KEY = "password";

    @Override
    public void initPlugin(Map<String, String> parameters) {
        // nothing to do
    }

    @Override
    public UserIdentificationInfo handleRetrieveIdentity(HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String username = httpRequest.getParameter(DUMMY_AUTH_FORM_USERNAME_KEY);
        String password = httpRequest.getParameter(DUMMY_AUTH_FORM_PASSWORD_KEY);
        if (isBlank(username) || isBlank(password)) {
            return null;
        }
        // dummy check: username = password to authenticate
        if (!username.equals(password)) {
            return null;
        }
        return new UserIdentificationInfo(username, password);
    }

    @Override
    public Boolean needLoginPrompt(HttpServletRequest httpRequest) {
        return FALSE; // TODO
    }

    @Override
    public List<String> getUnAuthenticatedURLPrefix() {
        return null; // TODO
    }

    @Override
    public Boolean handleLoginPrompt(HttpServletRequest httpRequest, HttpServletResponse httpResponse, String baseURL) {
        return FALSE; // TODO
    }

    @Override
    public Boolean handleLogout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return FALSE; // TODO
    }

}
