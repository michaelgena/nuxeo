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
package org.nuxeo.ecm.platform.web.common.requestcontroller.filter;

import static com.google.common.net.HttpHeaders.ORIGIN;
import static com.google.common.net.HttpHeaders.REFERER;
import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpTrace;
import org.nuxeo.ecm.platform.web.common.requestcontroller.service.RequestControllerManager;
import org.nuxeo.ecm.platform.web.common.vh.VirtualHostHelper;
import org.nuxeo.runtime.api.Framework;

import com.thetransactioncompany.cors.CORSConfiguration;
import com.thetransactioncompany.cors.CORSFilter;
import com.thetransactioncompany.cors.Origin;

/**
 * Nuxeo CORS and CSRF filter, returning CORS configuration and preventing CSRF attacks by rejecting dubious requests.
 *
 * @since 5.7.2 for CORS
 * @since 10.1 for CSRF
 */
public class NuxeoCorsCsrfFilter implements Filter {

    private static final Log log = LogFactory.getLog(NuxeoCorsCsrfFilter.class);

    public static final String GET = HttpGet.METHOD_NAME;

    public static final String HEAD = HttpHead.METHOD_NAME;

    public static final String OPTIONS = HttpOptions.METHOD_NAME;

    public static final String TRACE = HttpTrace.METHOD_NAME;

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to do
    }

    @Override
    public void destroy() {
        // nothing to do
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        RequestControllerManager service = Framework.getService(RequestControllerManager.class);
        CORSFilter corsFilter = service.getCorsFilterForRequest(request);
        CORSConfiguration corsConfig = corsFilter == null ? null : corsFilter.getConfiguration();
        String method = request.getMethod();
        URI sourceURI = getSourceURI(request);
        URI targetURI = getTargetURI(request);
        if (log.isDebugEnabled()) {
            log.debug("Method: " + method + ", source: " + sourceURI + ", target: " + targetURI);
        }

        boolean allow;
        if (GET.equals(method) || HEAD.equals(method) || OPTIONS.equals(method) || TRACE.equals(method)) {
            // safe method according to RFC 7231 4.2.1
            log.debug("Safe method: allow");
            allow = true;
        } else if (sourceAndTargetMatch(sourceURI, targetURI)) {
            // source and target match, or not provided
            log.debug("Source and target match: allow");
            if (targetURI == null) {
                // misconfigured server or proxy headers
                log.error("Cannot determine target URL for CSRF check");
            }
            allow = true;
        } else if (corsConfig == null) {
            // source not known by CORS config: be safe and disallow
            log.debug("URL not covered by CORS config: disallow cross-site request");
            allow = false;
        } else if (!corsConfig.isAllowedOrigin(new Origin(sourceURI.toString()))) {
            // not in allowed CORS origins
            log.debug("Origin not allowed by CORS config: disallow cross-site request");
            allow = false;
        } else if (!corsConfig.isSupportedMethod(method)) {
            // not in allowed CORS methods
            log.debug("Method not allowed by CORS config: disallow cross-site request");
            allow = false;
        } else {
            log.debug("Origin and method allowed by CORS config: allow cross-site request");
            allow = true;
        }

        if (allow) {
            if (corsFilter == null) {
                chain.doFilter(request, response);
            } else {
                corsFilter.doFilter(request, response, chain);
            }
            return;
        }

        // disallowed cross-site request
        String message = "CSRF check failure";
        log.warn(message + ": source: " + sourceURI + " does not match target: " + targetURI
                + " and not allowed by CORS config");
        response.sendError(HttpServletResponse.SC_FORBIDDEN, message);
    }

    /** Gets the source URI: the URI of the page from which the request is actually coming. */
    public URI getSourceURI(HttpServletRequest request) {
        String source = request.getHeader(ORIGIN);
        if (isBlank(source)) {
            source = request.getHeader(REFERER);
        }
        if (isBlank(source)) {
            return null;
        }
        source = source.trim();
        if ("null".equals(source)) {
            // RFC 6454 7.1 origin-list-or-null
            return null;
        }
        if (source.contains(" ")) {
            // RFC 6454 7.1 origin-list
            // keep only the first origin to simplify the logic (nobody sends two origins anyway)
            source = source.substring(0, source.indexOf(' '));
        }
        try {
            return new URI(source);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Gets the target URI: the URI to which the browser is connecting. */
    public URI getTargetURI(HttpServletRequest request) {
        String baseURL = VirtualHostHelper.getServerURL(request, false);
        if (baseURL == null) {
            return null;
        }
        try {
            return new URI(baseURL);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public boolean sourceAndTargetMatch(URI sourceURI, URI targetURI) {
        if (sourceURI == null || targetURI == null) {
            return true;
        }
        return Objects.equals(sourceURI.getScheme(), targetURI.getScheme()) //
                && Objects.equals(sourceURI.getHost(), targetURI.getHost()) //
                && sourceURI.getPort() == targetURI.getPort();
    }

}
