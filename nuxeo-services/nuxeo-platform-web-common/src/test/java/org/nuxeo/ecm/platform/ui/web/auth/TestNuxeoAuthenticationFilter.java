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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.nuxeo.ecm.platform.ui.web.auth.DummyAuthenticationPluginAnonymous.DUMMY_ANONYMOUS_LOGIN;
import static org.nuxeo.ecm.platform.ui.web.auth.DummyAuthenticationPluginForm.DUMMY_AUTH_FORM_PASSWORD_KEY;
import static org.nuxeo.ecm.platform.ui.web.auth.DummyAuthenticationPluginForm.DUMMY_AUTH_FORM_USERNAME_KEY;

import java.io.IOException;
import java.security.Principal;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventProducer;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.mockito.MockitoFeature;
import org.nuxeo.runtime.mockito.RuntimeService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.RuntimeFeature;

@RunWith(FeaturesRunner.class)
@Features({ RuntimeFeature.class, MockitoFeature.class })
@Deploy("org.nuxeo.ecm.platform.web.common:OSGI-INF/authentication-framework.xml")
public class TestNuxeoAuthenticationFilter {

    // from NuxeoAuthenticationFilter
    protected static final String BYPASS_AUTHENTICATION_LOG = "byPassAuthenticationLog";

    // from NuxeoAuthenticationFilter
    protected static final String SECURITY_DOMAIN = "securityDomain";

    // from NuxeoAuthenticationFilter
    protected static final String LOGIN_SUCCESS = "loginSuccess";

    @Mock
    @RuntimeService
    protected UserManager userManager;

    @Mock
    @RuntimeService
    protected EventProducer eventProducer;

    protected NuxeoAuthenticationFilter filter;

    protected DummyFilterChain chain;

    protected ArgumentCaptor<Event> eventCaptor;

    public static class DummyFilterConfig implements FilterConfig {

        protected final Map<String, String> initParameters;

        public DummyFilterConfig(Map<String, String> initParameters) {
            this.initParameters = initParameters;
        }

        @Override
        public String getFilterName() {
            return "NuxeoAuthenticationFilter";
        }

        @Override
        public ServletContext getServletContext() {
            return null;
        }

        @Override
        public String getInitParameter(String name) {
            return initParameters.get(name);
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.enumeration(initParameters.keySet());
        }
    }

    public static class DummyFilterChain implements FilterChain {

        protected boolean called;

        protected Principal principal;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
            called = true;
            principal = ((HttpServletRequest) request).getUserPrincipal();
        }
    }

    @Before
    public void setUp() throws Exception {
        // filter config
        Map<String, String> initParameters = new HashMap<>();
        initParameters.put(BYPASS_AUTHENTICATION_LOG, "false");
        initParameters.put(SECURITY_DOMAIN, NuxeoAuthenticationFilter.LOGIN_DOMAIN);
        FilterConfig config = new DummyFilterConfig(initParameters);
        // filter
        filter = new NuxeoAuthenticationFilter();
        filter.init(config);
        // filter chain
        chain = new DummyFilterChain();
        // TODO prefilters

        // usemanager
        when(userManager.getAnonymousUserId()).thenReturn(DUMMY_ANONYMOUS_LOGIN);
        // events
        eventCaptor = ArgumentCaptor.forClass(Event.class);
    }

    @After
    public void tearDown() {
        filter.destroy();
    }

    /**
     * No auth chain configured.
     */
    @Test
    public void testNoAuthenticationPlugins() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/foo/bar");
        when(request.getContextPath()).thenReturn("/nuxeo");
        when(request.getUserPrincipal()).thenReturn(null);
        when(request.getSession(eq(false))).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertTrue(chain.called);
        assertNull(chain.principal);
    }

    /**
     * Basic immediate login. Not saved in cache.
     */
    @Test
    @Deploy("org.nuxeo.ecm.platform.web.common.test:OSGI-INF/test-authchain-dummy-anonymous.xml")
    public void testAuthenticationPluginAnonymous() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/foo/bar");
        when(request.getContextPath()).thenReturn("/nuxeo");
        when(request.getUserPrincipal()).thenReturn(null);
        when(request.getSession(eq(false))).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertTrue(chain.called);
        assertEquals(DUMMY_ANONYMOUS_LOGIN, chain.principal.getName());

        verify(eventProducer).fireEvent(eventCaptor.capture());
        List<Event> events = eventCaptor.getAllValues();
        assertEquals(1, events.size());
        Event event = events.get(0);
        assertEquals(LOGIN_SUCCESS, event.getName());
    }

    /**
     * Basic immediate login. Saved in cache.
     */
    @Test
    @Deploy("org.nuxeo.ecm.platform.web.common.test:OSGI-INF/test-authchain-dummy-form.xml")
    public void testAuthenticationPluginForm() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getRequestURI()).thenReturn("/foo/bar");
        when(request.getContextPath()).thenReturn("/nuxeo");
        when(request.getUserPrincipal()).thenReturn(null);
        when(request.getParameter(eq(DUMMY_AUTH_FORM_USERNAME_KEY))).thenReturn("bob");
        when(request.getParameter(eq(DUMMY_AUTH_FORM_PASSWORD_KEY))).thenReturn("bob");
        Map<String, Object> attributes = new HashMap<>();
        mockSession(session, attributes);
        when(request.getSession(anyBoolean())).thenReturn(session);

        filter.doFilter(request, response, chain);

        assertTrue(chain.called);
        assertEquals("bob", chain.principal.getName());

        verify(eventProducer).fireEvent(eventCaptor.capture());
        List<Event> events = eventCaptor.getAllValues();
        assertEquals(1, events.size());
        Event event = events.get(0);
        assertEquals(LOGIN_SUCCESS, event.getName());
    }

    protected void mockSession(HttpSession session, Map<String, Object> attributes) {
        doAnswer(i -> {
            String key = (String) i.getArguments()[0];
            return attributes.get(key);
        }).when(session).getAttribute(anyString());
        doAnswer(i -> {
            String key = (String) i.getArguments()[0];
            Object value = i.getArguments()[1];
            attributes.put(key, value);
            return null;
        }).when(session).setAttribute(anyString(), any());
    }

}
