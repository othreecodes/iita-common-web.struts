/**
 * iita-common Apr 22, 2010
 */
package org.iita.service.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.hibernate.hql.ast.QuerySyntaxException;
import org.iita.query.model.Query;
import org.iita.query.model.QueryParameter;
import org.iita.security.Authorize;
import org.iita.security.model.User;
import org.iita.service.BatchDataProvider;
import org.iita.service.BatchTemplater;
import org.iita.service.QueryService;
import org.iita.util.DateUtil;
import org.iita.util.DeleteFileAfterCloseInputStream;
import org.iita.util.PagedResult;
import org.springframework.security.AccessDeniedException;
import org.springframework.security.annotation.Secured;
import org.springframework.transaction.annotation.Transactional;

/**
 * QueryService provides implementation of custom query management
 * 
 * @author mobreza
 */
public class QueryServiceImpl implements QueryService {
	/** Default roles that can run queries */
	private static final String DEFAULT_RUN_ROLES = "ROLE_QUERY,ROLE_QUERYMANAGER,ROLE_ADMIN,ROLE_PARTNERADMIN,ROLE_PARTNERREADALL";
	/** LOG. */
	private static final Log LOG = LogFactory.getLog(QueryServiceImpl.class);
	private EntityManager entityManager;
	/** Extensions to query service */
	private Map<String, Object> extensions = new Hashtable<String, Object>();

	/**
	 * Generated query. Contains service calculated parameters such as {TODAY}, {NOW}, {TODAY+3m} etc.
	 * 
	 * @author mobreza
	 */
	private class GeneratedQuery {
		public Hashtable<String, Object> generatedParameters;
		public String query;
	}

	/**
	 * @param extensions the extensions to set
	 */
	public void setExtensions(Map<String, Object> extensions) {
		this.extensions = extensions;
	}

	/**
	 * @param entityManager the entityManager to set
	 */
	@PersistenceContext
	public void setEntityManager(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	@Transactional(readOnly = true)
	public org.iita.query.model.Query loadQuery(Long id) {
		return this.entityManager.find(Query.class, id);
	}

	@Override
	@Transactional(readOnly = false)
	@Secured( { "ROLE_QUERYMANAGER", "ROLE_ADMIN", "ROLE_PARTNERADMIN", "ROLE_PARTNERREADALL" })
	public void update(Query query) throws QuerySyntaxException {
		if (query.getQuery().startsWith("SERVICE")) {

		} else {
			// try parsing
			try {
				@SuppressWarnings("unused")
				javax.persistence.Query jpaQuery = this.entityManager.createQuery(getGeneratedQuery(query.getQuery()).query);
			} catch (QuerySyntaxException e) {
				LOG.error("Error parsing user query: " + e.getMessage());
				throw e;
			}
		}
		if (query.getId() == null)
			this.entityManager.persist(query);
		else
			this.entityManager.merge(query);
	}
	
	@Override
	@Transactional(readOnly = false)
	@Secured( { "ROLE_QUERYMANAGER", "ROLE_ADMIN", "ROLE_PARTNERADMIN", "ROLE_PARTNERREADALL" })
	public Query updateSearch(Query query) throws QuerySyntaxException {
		if (query.getQuery().startsWith("SERVICE")) {

		} else {
			// try parsing
			try {
				@SuppressWarnings("unused")
				javax.persistence.Query jpaQuery = this.entityManager.createQuery(getGeneratedQuery(query.getQuery()).query);
			} catch (QuerySyntaxException e) {
				LOG.error("Error parsing user query: " + e.getMessage());
				throw e;
			}
		}
		if (query.getId() == null)
			this.entityManager.persist(query);
		else
			this.entityManager.merge(query);
		
		return query;
	}

	/**
	 * @see org.iita.service.QueryService#remove(org.iita.query.model.Query)
	 */
	@Override
	@Transactional
	@Secured( { "ROLE_QUERYMANAGER", "ROLE_ADMIN", "ROLE_PARTNERADMIN", "ROLE_PARTNERREADALL" })
	public void remove(Query query) {
		this.entityManager.remove(query);
	}

	/**
	 * @see org.iita.service.QueryService#list(org.iita.security.model.User)
	 */
	@SuppressWarnings("unchecked")
	@Override
	@Transactional(readOnly = true)
	public List<Query> list(User user) {
		List<Query> queries = this.entityManager.createQuery("from Query q where title not like 'Title:%' order by q.title").getResultList();
		// return all queries
		if (Authorize.hasAny(DEFAULT_RUN_ROLES))
			return queries;

		// check individual queries
		List<Query> allowedQueries = new ArrayList<Query>();
		for (Query query : queries) {
			try {
				validateQueryAccess(query);
				allowedQueries.add(query);
			} catch (AccessDeniedException e) {

			}
		}

		return allowedQueries;
	}

	/**
	 * @see org.iita.service.QueryService#listForRole(java.lang.String)
	 */
	@SuppressWarnings("unchecked")
	@Override
	@Transactional(readOnly = true)
	public List<Query> listForRole(String role) {
		return this.entityManager.createQuery("select q from Query q where locate(upper(q.allowedRoles), :role)>=0 and allowedRoles NOT IN('ROLE_IPM','ROLE_CHECKLISTSUBMISSION','ROLE_MandE','ROLE_SUPPLYCHAIN','ROLE_COMMUNICATION','ROLE_HUMANSUBJECT') and title not like 'Title:%'").setParameter("role", role.toUpperCase())
				.getResultList();
	}

	/**
	 * @see org.iita.service.QueryService#executeQuery(org.iita.query.model.Query, int, int)
	 */
	@Override
	@Transactional(readOnly = true)
	public PagedResult<?> executeQuery(Query query, int startAt, int maxResults) {
		// check needed roles
		validateQueryAccess(query);

		if (query.getQuery().startsWith("SERVICE")) {
			return executeServiceQuery(query, startAt, maxResults);
		} else {
			return executeJPAQuery(query, startAt, maxResults);
		}
	}
	
	/**
	 * @see org.iita.service.QueryService#executeAlumniSearchQuery(org.iita.query.model.Query, int, int)
	 */
	@Override
	@Transactional(readOnly = true)
	public PagedResult<?> executeAlumniSearchQuery(String query, int startAt, int maxResults) {
		// check needed roles
		//validateQueryAccess(query);

		return executeTestJPAQuery(query, startAt, maxResults);
	}

	/**
	 * @param query
	 * @param startAt
	 * @param maxResults
	 * @return
	 */
	private PagedResult<?> executeServiceQuery(Query query, int startAt, int maxResults) {
		Matcher matcher = Pattern.compile("^SERVICE:(\\w+)\\.(\\w+)\\(\\)$").matcher(query.getQuery());
		if (matcher.matches()) {
			LOG.info("Attempting to execute service query");
			String serviceName = matcher.group(1);
			String methodName = matcher.group(2);

			LOG.info("Service Name: " + serviceName);
			LOG.info("Method Name: " + methodName);
			LOG.debug("Service: " + serviceName + " Method: " + methodName);

			Object service = this.extensions.get(serviceName);
			if (service == null) {
				LOG.warn("Query extension service '" + serviceName + "' not found.");
				throw new RuntimeException("Query extension service '" + serviceName + "' not found.");
			}
			Method method = null;
			Object returnValue = null;
			//returnValue = org.iita.promis.service.PromisCRMService.listProjectsBySpending100(0,10000);
			try {
				// find paged method
				method = service.getClass().getMethod(methodName, int.class, int.class);

				Class<?> returnType = method.getReturnType();
				LOG.debug("Method " + methodName + "(int, int). return type: " + returnType);
				LOG.info("MethodName " + methodName + "(int, int). return type: " + returnType);
				LOG.info("Method " + method);
				LOG.info("Service " + service);
				if (PagedResult.class.isAssignableFrom(returnType)) {
					LOG.info("Method returns PagedResult, assuming startAt and maxRecords are arguments");
					returnValue = method.invoke(service, startAt, maxResults);

					return toPagedResult(returnValue);
				} else {
					LOG.warn("Method return type is not PagedResult, skipping");
				}
			} catch (Exception e) {
				LOG.error(e, e);
				LOG.warn("Could not find method: " + methodName + ": " + e.getMessage());
			}

			try {
				// find no-arguments
				method = service.getClass().getMethod(methodName);

				Class<?> returnType = method.getReturnType();
				LOG.warn("Method return type: " + returnType);
				LOG.info("Method return type: " + returnType);
				LOG.info("Method " + method);
				LOG.info("Service " + service);
				if (returnType != Void.TYPE) {
					returnValue = method.invoke(service);

					return toPagedResult(returnValue);
				} else {
					LOG.warn("Method is void! Not executing");
				}
			} catch (Exception e) {
				LOG.error(e, e);
				LOG.warn("Could not find method: " + methodName + ": " + e.getMessage());
			}

			LOG.warn("No appropriate method found to execute service call");
			throw new RuntimeException("No appropriate method found to execute service call");
		} else {
			throw new RuntimeException("Invalid service query: " + query.getQuery());
		}
	}

	/**
	 * @param returnValue
	 * @return
	 */
	private PagedResult<?> toPagedResult(Object returnValue) {
		// need to convert to PagedResult
		if (returnValue == null)
			return null;
		if (PagedResult.class.isAssignableFrom(returnValue.getClass()))
			return (PagedResult<?>) returnValue;
		if (List.class.isAssignableFrom(returnValue.getClass())) {
			List<?> list = (List<?>) returnValue;
			PagedResult<?> paged = new PagedResult<Object>(0, list.size());
			paged.setResults(list);
			paged.setTotalHits(list.size());
			return paged;
		}
		throw new RuntimeException("Don't know how to convert " + returnValue + " to PagedResult");
	}

	/**
	 * Execute JPA query
	 * 
	 * @param query
	 * @param startAt
	 * @param maxResults
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private PagedResult<?> executeJPAQuery(Query query, int startAt, int maxResults) {
		javax.persistence.Query jpaQuery = getJPAQuery(query);
		LOG.debug("Executing data query");
		jpaQuery.setFirstResult(startAt).setMaxResults(maxResults);

		PagedResult<Object[]> paged = new PagedResult<Object[]>(startAt, maxResults);
		paged.setResults(jpaQuery.getResultList());
		if (paged.getResults().size() > 0) {
			LOG.debug("Finding total hits");
			GeneratedQuery generatedQuery = getGeneratedQuery(getCountQuery(query.getQuery()));

			try {
				javax.persistence.Query jpaTotalQuery = this.entityManager.createQuery(generatedQuery.query);

				for (String generatedParameter : generatedQuery.generatedParameters.keySet()) {
					LOG.debug("Setting parameter: " + generatedParameter + " = " + generatedQuery.generatedParameters.get(generatedParameter));
					jpaTotalQuery.setParameter(generatedParameter, generatedQuery.generatedParameters.get(generatedParameter));
				}

				if (query.getParameters() != null)
					for (QueryParameter qp : query.getParameters()) {
						jpaTotalQuery.setParameter(qp.getName(), qp.getValue());
					}
				long totalHits = 0;

				for (Long subCount : (List<Long>) jpaTotalQuery.getResultList())
					totalHits += subCount;

				paged.setTotalHits(totalHits);
			} catch (Exception e) {
				LOG.warn("Could not execute count query: " + generatedQuery.query);
				if (paged.getResults().size() < paged.getPages())
					paged.setTotalHits(paged.getStartAt() + paged.getResults().size());
				else
					paged.setTotalHits(paged.getStartAt() + 5 * paged.getPageSize());
			}
		}
		return paged;
	}
	
	/**
	 * Execute JPA query
	 * 
	 * @param query
	 * @param startAt
	 * @param maxResults
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private PagedResult<?> executeTestJPAQuery(String query, int startAt, int maxResults) {
		javax.persistence.Query jpaQuery = getTestJPAQuery(query);
		LOG.debug("Executing data query");
		jpaQuery.setFirstResult(startAt).setMaxResults(maxResults);

		PagedResult<Object[]> paged = new PagedResult<Object[]>(startAt, maxResults);
		paged.setResults(jpaQuery.getResultList());
		if (paged.getResults().size() > 0) {
			LOG.debug("Finding total hits");
			GeneratedQuery generatedQuery = getGeneratedQuery(getCountQuery(query));

			try {
				javax.persistence.Query jpaTotalQuery = this.entityManager.createQuery(generatedQuery.query);

				for (String generatedParameter : generatedQuery.generatedParameters.keySet()) {
					LOG.debug("Setting parameter: " + generatedParameter + " = " + generatedQuery.generatedParameters.get(generatedParameter));
					jpaTotalQuery.setParameter(generatedParameter, generatedQuery.generatedParameters.get(generatedParameter));
				}

				long totalHits = 0;

				for (Long subCount : (List<Long>) jpaTotalQuery.getResultList())
					totalHits += subCount;

				paged.setTotalHits(totalHits);
			} catch (Exception e) {
				LOG.warn("Could not execute count query: " + generatedQuery.query);
				if (paged.getResults().size() < paged.getPages())
					paged.setTotalHits(paged.getStartAt() + paged.getResults().size());
				else
					paged.setTotalHits(paged.getStartAt() + 5 * paged.getPageSize());
			}
		}
		return paged;
	}

	/**
	 * Validate query acces. If no roles are specified, ROLE_QUERY, QUERY_MANAGER or ROLE_ADMIN is required.
	 * 
	 * @param query
	 */
	private void validateQueryAccess(Query query) {
		String roles = query.getAllowedRoles();

		// always allowed to run query
		if (Authorize.hasAny(DEFAULT_RUN_ROLES))
			return;

		// check if has access
		if (roles == null || !Authorize.hasAny(roles)) {
			LOG.info("USER ROLES: " + roles);
			
			throw new AccessDeniedException("Query '" + query.getTitle() + "' requires ROLE_QUERY to be run");
		}
	}

	/**
	 * @param query
	 * @return
	 */
	String getCountQuery(String query) {
		if (query.contains("group by")) {
			Pattern p = Pattern.compile("from\\s(.+)\\sgroup\\s+by\\s+([\\w\\.\\d]+(\\s*,\\s*[\\w\\d\\.]+)*)(\\s+having\\s+.+)?", Pattern.CASE_INSENSITIVE
					| Pattern.DOTALL);
			LOG.debug("Source query: " + query);
			Matcher m = p.matcher(query);
			if (m.find()) {
				p = Pattern.compile("([^,]+)");
				LOG.debug("Group by source: " + m.group(2));
				Matcher m2 = p.matcher(m.group(2));
				String countQuery;
				if (m2.find()) {
					String groupBy = "";
					//String countQuery = "select count(" + m.group(2) + ") from " + m.group(1);
					countQuery = "select count(distinct " + m2.group(1) + ") from " + m.group(1);
					groupBy = " group by " + m2.group(1);
					if (m2.find()) {
						LOG.debug("Found additional group by clauses: " + m2.group(1));
						countQuery += " group by " + m2.group(1);
					}

					while (m2.find()) {
						LOG.debug("Found additional group by clauses: " + m2.group(1));
						countQuery += ", " + m2.group(1);
						groupBy += ", " + m2.group(1);
					}

					if (m.group(4) != null) {
						LOG.debug("Group by: " + groupBy);
						countQuery += groupBy + m.group(4);
					}
				} else {
					countQuery = "select count(*) from " + m.group(1);
				}

				LOG.debug("Group-by Count query: " + countQuery);
				return countQuery;
			} else {
				LOG.error("Could not match query");
				throw new RuntimeException("Could not parse group-by query: " + query);
			}
		} else {
			Pattern p = Pattern.compile("^(.+)\\s*from", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
			String countQuery = p.matcher(query).replaceFirst("select count(*) from");
			LOG.debug("Count query: " + countQuery);
			return countQuery;
		}
	}

	/**
	 * @param query
	 * @return
	 */
	private javax.persistence.Query getJPAQuery(Query query) {

		GeneratedQuery generatedQuery = getGeneratedQuery(query.getQuery());

		LOG.debug("Constructing jpa data query: " + generatedQuery.query);

		javax.persistence.Query jpaQuery = this.entityManager.createQuery(generatedQuery.query);

		LOG.debug("Adding params to query");
		for (String generatedParameter : generatedQuery.generatedParameters.keySet()) {
			LOG.debug("Setting parameter: " + generatedParameter + " = " + generatedQuery.generatedParameters.get(generatedParameter));
			jpaQuery.setParameter(generatedParameter, generatedQuery.generatedParameters.get(generatedParameter));
		}

		if (query.getParameters() != null)
			for (QueryParameter qp : query.getParameters()) {
				jpaQuery.setParameter(qp.getName(), qp.getValue());
			}
		return jpaQuery;
	}
	
	/**
	 * @param query
	 * @return
	 */
	private javax.persistence.Query getTestJPAQuery(String query) {

		GeneratedQuery generatedQuery = getGeneratedQuery(query);

		LOG.debug("Constructing jpa data query: " + generatedQuery.query);

		javax.persistence.Query jpaQuery = this.entityManager.createQuery(generatedQuery.query);

		LOG.debug("Adding params to query");
		for (String generatedParameter : generatedQuery.generatedParameters.keySet()) {
			LOG.debug("Setting parameter: " + generatedParameter + " = " + generatedQuery.generatedParameters.get(generatedParameter));
			jpaQuery.setParameter(generatedParameter, generatedQuery.generatedParameters.get(generatedParameter));
		}

		return jpaQuery;
	}

	/**
	 * This methods searches for constant {} expressions in the query and generates query data with appropriate parameters.
	 * 
	 * @param queryString
	 * @return
	 */
	private GeneratedQuery getGeneratedQuery(String queryString) {
		GeneratedQuery generatedQuery = new GeneratedQuery();

		StringBuilder sb = new StringBuilder();
		generatedQuery.generatedParameters = new Hashtable<String, Object>();

		Matcher matcher = Pattern.compile("\\{([^\\}]+)\\}").matcher(queryString);
		int generatedParamCount = 0;
		int matcherPos = 0;
		while (matcher.find()) {

			sb.append(queryString.substring(matcherPos, matcher.start()));
			matcherPos = matcher.start();
			LOG.debug("Found: " + matcher.group());
			sb.append(":GEN" + generatedParamCount);
			LOG.debug("QRY: " + sb.toString());

			Object generatedValue = calculateGeneratedValue(matcher.group(1));
			LOG.debug(matcher.group() + ": " + generatedValue);
			generatedQuery.generatedParameters.put("GEN" + generatedParamCount, generatedValue);
			generatedParamCount++;
			matcherPos = matcher.end();

		}
		LOG.debug("End QRY: " + sb.toString());
		if (matcherPos <= queryString.length())
			sb.append(queryString.substring(matcherPos, queryString.length()));
		LOG.debug("FINAL QUERY: " + sb.toString());

		generatedQuery.query = sb.toString();

		return generatedQuery;
	}

	/**
	 * Only supports simple expressions.
	 * <p>
	 * Time expressions:
	 * </p>
	 * <ul>
	 * <li>NOW - current date and time</li>
	 * <li>TODAY - current date, time set to 00:00</li>
	 * <li>YESTERDAY, YDAY - yesterday, time set to 00:00 (equal to TODAY-1d)</li>
	 * <li>Operations: +|- digit [d|y|m|h]</li>
	 * </ul>
	 * <p>
	 * User expression: USER
	 * </p>
	 * 
	 * @param group
	 * @return
	 */
	private Object calculateGeneratedValue(String expression) {
		LOG.debug("Getting value for expression: " + expression);
		if (expression.startsWith("NOW") || expression.startsWith("TODAY") || expression.startsWith("YESTERDAY") || expression.startsWith("YDAY")) {
			Calendar cal = GregorianCalendar.getInstance();
			Matcher matcher = Pattern.compile("(\\w+)((\\+|-)(\\d+)(d|m|y|h))?", Pattern.CASE_INSENSITIVE).matcher(expression);
			if (matcher.matches()) {
				String starter = matcher.group(1);
				if (starter.equalsIgnoreCase("NOW")) {
					// do nothing, cal is okay
				} else if (starter.equalsIgnoreCase("TODAY")) {
					// start of day
					DateUtil.resetHours(cal);
				} else if (starter.equalsIgnoreCase("TOMORROW")) {
					// start of day
					cal.add(Calendar.DATE, 1);
					DateUtil.resetHours(cal);
				} else if (starter.equalsIgnoreCase("YESTERDAY") || starter.equalsIgnoreCase("YDAY")) {
					// start of day
					cal.add(Calendar.DATE, -1);
					DateUtil.resetHours(cal);
				} else {
					LOG.error("Unsupported time start: " + starter);
					throw new RuntimeException("Unsupported time start: " + starter);
				}
				LOG.debug(String.format("Starter value: %1$tF %1$tT", cal));

				if (matcher.group(2) != null) {
					// have operation!
					boolean positive = matcher.group(3).equalsIgnoreCase("+");
					LOG.debug("Positive? " + positive);
					int amount = Integer.parseInt(matcher.group(4));
					LOG.debug("Amount=" + amount);
					String unit = matcher.group(5);
					LOG.debug("Units=" + unit);

					int field;
					if (unit.equalsIgnoreCase("d"))
						field = Calendar.DATE;
					else if (unit.equalsIgnoreCase("m"))
						field = Calendar.MONTH;
					else if (unit.equalsIgnoreCase("y"))
						field = Calendar.YEAR;
					else if (unit.equalsIgnoreCase("h"))
						field = Calendar.HOUR_OF_DAY;
					else {
						LOG.error("Unsupported time unit: " + unit + ". Supported units are: d (day), m (month), y (year), h (hour)");
						throw new RuntimeException("Unsupported time unit: " + unit + ". Supported units are: d (day), m (month), y (year), h (hour)");
					}
					cal.add(field, (positive ? amount : -amount));
				}
				LOG.debug(String.format("Returning value: %1$tF %1$tT as expression %2$s", cal, expression));
				return cal.getTime();
			} else {
				LOG.error("Error in time expression: " + expression);
				throw new RuntimeException("Error in time expression: " + expression);
			}
		} else if (expression.equalsIgnoreCase("USER") || expression.equalsIgnoreCase("USERNAME")) {
			User currentUser = Authorize.getUser();
			LOG.debug("Current user: " + currentUser);
			if (expression.equalsIgnoreCase("USER"))
				return currentUser;
			else
				return currentUser == null ? null : currentUser.getUsername();
		} else {
			LOG.error("Unsupported expression: " + expression);
			throw new RuntimeException("Unsupported expression: " + expression);
		}
	}

	/**
	 * Plain Object[] to Excel file conversion
	 * 
	 * @throws IOException
	 * @see org.iita.service.QueryService#executeXLS(org.iita.query.model.Query)
	 */
	@SuppressWarnings("unchecked")
	@Override
	@Transactional(readOnly = true)
	public InputStream executeXLS(Query query) throws IOException {
		// check access rights
		validateQueryAccess(query);

		javax.persistence.Query jpaQuery = getJPAQuery(query);

		List<Object[]> data = jpaQuery.getResultList();

		HSSFWorkbook wb = new HSSFWorkbook();
		HSSFSheet sheet = wb.createSheet(query.getTitle());
		String[] headings = query.getHeadings();
		XLSExportService.fillSheet(sheet, headings, data);

		File file = File.createTempFile("query", "xls");
		FileOutputStream fs = new FileOutputStream(file);
		wb.write(fs);
		fs.flush();
		fs.close();
		return new DeleteFileAfterCloseInputStream(file);
	}

	@Override
	@Transactional(readOnly = true)
	public InputStream executeHtml2XLS(final Query query, BatchTemplater batchTemplater) throws IOException {

		// check access rights
		validateQueryAccess(query);

		//		final javax.persistence.Query jpaQuery = getJPAQuery(query);

		HSSFWorkbook wb = new HSSFWorkbook();
		HSSFSheet sheet = wb.createSheet(query.getTitle());

		PipedOutputStream pipedOutputStream = new PipedOutputStream();
		batchTemplater.setOutputStream(pipedOutputStream);

		PipedInputStream pipedInputStream = new PipedInputStream(pipedOutputStream, 4096);
		Html2Excel html2Excel = new Html2Excel(sheet, pipedInputStream);

		Thread converter = new Thread(html2Excel);
		converter.start();

		// want to iterate over data in batches of 100
		final int batchSize = 100;

		batchTemplater.run(new BatchDataProvider() {
			private int pos = 0;

			@SuppressWarnings("unchecked")
			@Override
			public List<Object[]> fetchData() {
				LOG.debug("Fetching next batch of data (" + pos + ", " + batchSize + ")");
				List<Object[]> data;
				if (query.getQuery().startsWith("SERVICE")) {
					data = (List<Object[]>) executeServiceQuery(query, pos, batchSize).getResults();
				} else {
					final javax.persistence.Query jpaQuery = getJPAQuery(query);
					data = jpaQuery.setMaxResults(batchSize).setFirstResult(pos).getResultList();
				}
				pos += data.size();
				LOG.info("New position: " + pos);
				return data;

				//				List<Object[]> data = jpaQuery.setMaxResults(batchSize).setFirstResult(pos).getResultList();
				//				pos += data.size();
				//				LOG.info("New position: " + pos);
				//				return data;
			}
		});

		LOG.info("Waiting for converter to finish");
		try {
			converter.join();
		} catch (InterruptedException e) {
			LOG.error(e, e);
		}

		File file = File.createTempFile("query", "xls");
		FileOutputStream fs = new FileOutputStream(file);
		wb.write(fs);
		fs.flush();
		fs.close();
		return new DeleteFileAfterCloseInputStream(file);
	}
}
