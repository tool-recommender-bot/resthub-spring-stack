package org.resthub.oauth2.filter.front;

import java.io.IOException;
import java.util.Date;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.resthub.oauth2.filter.service.ValidationService;
import org.resthub.oauth2.provider.exception.ProtocolException.Error;
import org.resthub.oauth2.provider.model.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * OAuth 2 Servlet filter to protect resources on a Resource server.
 * <ul>
 * <li>Extract the access token from the request</li>
 * <li>Gets the corresponding information with the Authorization server</li>
 * <li>Validate life time and scope</li>
 * <li>Process if possible the request.</li>
 * </ul>
 */
@Named("oauth2Filter")
public class OAuth2Filter implements Filter {

	// -----------------------------------------------------------------------------------------------------------------
	// Private attributes

	/**
	 * Class logger.
	 */
	protected Logger logger = LoggerFactory.getLogger(getClass());

	/**
	 * The validation service. managed by Spring.
	 */
	@Inject
	protected ValidationService service;

	/**
	 * Resource protected and served by this server.
	 */
	@Value("#{securityConfig.resourceName}")
	protected String resource = "";

	/**
	 * Constants storing the HTTP parameter name used to extract the access
	 * token.
	 */
	protected static final String ACCESSTOKEN_PARAMETER = "oauth_token";

	// -----------------------------------------------------------------------------------------------------------------
	// Protected methods

	/**
	 * Sets the WWW-Authenticate response header to explain the reject of an
	 * incoming request.
	 * 
	 * @param response
	 *            The HTTP Response, enriched with header.
	 * @param error
	 *            The error case.
	 * @param description
	 *            The error description.
	 * @param errorStatus
	 *            The HTTP response code.
	 */
	protected void setError(HttpServletResponse response, String error, String description, Status errorStatus) {
		StringBuilder sb = new StringBuilder();
		sb.append("Token realm=\"").append(resource).append("\"");
		if (error != null) {
			sb.append(", error=\"").append(error).append("\"");
			if (description != null) {
				sb.append(", error-description=\"").append(description).append("\"");
			}
		}
		// Sets the autication header.
		response.setStatus(errorStatus.getStatusCode());
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, sb.toString());
	} // setError().

	// -----------------------------------------------------------------------------------------------------------------
	// Public Filter inherrited methods

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void init(FilterConfig config) throws ServletException {
		// Emtpy
		logger.trace("[init] OAuth 2 filter initialization");

	} // init().

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void destroy() {
		// Emtpy
		logger.trace("[destroy] OAuth 2 filter finalization");
	} // destroy().

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void doFilter(ServletRequest rawRequest, ServletResponse rawResponse, FilterChain chain) throws IOException,
			ServletException {

		// Only for HTTP requests.
		if (rawRequest instanceof HttpServletRequest && rawResponse instanceof HttpServletResponse) {
			HttpServletRequest request = (HttpServletRequest) rawRequest;
			HttpServletResponse response = (HttpServletResponse) rawResponse;

			Token token = null;
			
			logger.trace("[doFilter] Filters request {}", request.getRequestURL());
			// Extract Authorization Header Request.
			String tokenValue = request.getHeader(HttpHeaders.AUTHORIZATION);
			String otherValue = null;
			
			String method = request.getMethod();
			// Specification says that boty parameter may not be extracted
			// systematically.
			if (request.getHeader(HttpHeaders.CONTENT_TYPE) == MediaType.APPLICATION_FORM_URLENCODED
					&& (method == HttpMethod.POST || method == HttpMethod.DELETE || method == HttpMethod.PUT)) {
				otherValue = request.getParameter(ACCESSTOKEN_PARAMETER);
			} else {
				otherValue = request.getParameter(ACCESSTOKEN_PARAMETER);
			}
			if(tokenValue == null && otherValue == null) {
				// No token at all.
				logger.trace("[doFilter] No token found");
				setError(response, Error.UNAUTHORIZED_REQUEST.value(), null, Error.UNAUTHORIZED_REQUEST.status());
			} else if (tokenValue != null && otherValue != null) {
				// Too lany tokens !
				String error = "More than one method used to exchange token";
				logger.trace("[doFilter] {}", error);
				setError(response, Error.INVALID_REQUEST.value(), error, Error.INVALID_REQUEST.status());
			} else {
				// Just one
				tokenValue = tokenValue == null ? otherValue : tokenValue;
				if (tokenValue.matches("^Token token=\".*\"$")) {
					// Extracts the token
					String accessToken = tokenValue.replace("Token token=\"", "");
					accessToken = accessToken.substring(0, accessToken.length() - 1);
					logger.trace("[doFilter] Accessing with accessToken '{}'", accessToken);
					// Match the token.
					try {
						token = service.validateToken(accessToken);
					} catch (Exception exc) {
						logger.warn("[doFilter] Cannot process request for {}: {}", request.getRequestURL(), exc
								.getMessage());
						logger.warn("[doFilter] Cause: ", exc);
						setError(response, null, null, Status.INTERNAL_SERVER_ERROR);
					}
					if (token == null) {
						// Unknown token
						logger.trace("[doFilter] Unknown token '{}'", accessToken);
						setError(response, Error.INVALID_TOKEN.value(), "Unvalid token", Error.INVALID_TOKEN
								.status());
					} else {
						// Check token expiration
						Date now = new Date();
						Long expired = (now.getTime() - token.createdOn.getTime()) / 1000;
						if (expired > token.lifeTime) {
							logger.trace("[doFilter] Expired token '{}'", accessToken);
							StringBuilder sb = new StringBuilder("Token has expired ").append(
									expired - token.lifeTime).append("s ago");
							setError(response, Error.EXPIRED_TOKEN.value(), sb.toString(), Error.EXPIRED_TOKEN
									.status());
							// Block processing.
							token = null;
						}
					}
				} else {
					// invalid token
					StringBuilder sb = new StringBuilder("The token passed is misformated");
					logger.trace("[doFilter] {}", sb.toString());
					setError(response, Error.INVALID_REQUEST.value(), sb.toString(), Error.INVALID_REQUEST.status());
				}
			}
			if (token != null) {
				// Process request.
				chain.doFilter(new SecuredHttpRequest(token.userId, token.permissions, request), rawResponse);
			}
		}
	} // doFilter().

} // class OAuth2Filter.