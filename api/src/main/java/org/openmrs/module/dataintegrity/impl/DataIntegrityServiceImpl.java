/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.module.dataintegrity.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import java.util.UUID;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.dataintegrity.DataIntegrityConstants;
import org.openmrs.module.dataintegrity.DataIntegrityService;
import org.openmrs.module.dataintegrity.IntegrityCheck;
import org.openmrs.module.dataintegrity.IntegrityCheckColumn;
import org.openmrs.module.dataintegrity.IntegrityCheckResult;
import org.openmrs.module.dataintegrity.IntegrityCheckRun;
import org.openmrs.module.dataintegrity.QueryResult;
import org.openmrs.module.dataintegrity.QueryResults;
import org.openmrs.module.dataintegrity.db.DataIntegrityDAO;
import org.openmrs.module.dataintegrity.executors.CountCheckExecutor;
import org.openmrs.module.dataintegrity.executors.ICheckExecutor;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.util.StringUtils;

/**
 * Implementation of DataIntegrityService
 * 
 * @see org.openmrs.module.dataintegrity.DataIntegrityService
 */
public class DataIntegrityServiceImpl implements DataIntegrityService {

	/**
	 * dao for use with this service implementation
	 */
	private DataIntegrityDAO dao;
	/**
	 * cache of executors
	 */
	private Map<String, ICheckExecutor> executors = null;

	protected final Log log = LogFactory.getLog(getClass());
	
	/**
	 * @see org.openmrs.module.dataintegrity.DataIntegrityService#setDataIntegrityDAO(DataIntegrityDAO)
	 */
	public void setDataIntegrityDAO(DataIntegrityDAO dao) {
		this.dao = dao;
	}

	/**
	 * @see org.openmrs.module.dataintegrity.DataIntegrityService#getDataIntegrityDAO()
	 */
	public DataIntegrityDAO getDataIntegrityDAO() {
		return this.dao;
	}

	/**
	 * @see org.openmrs.module.dataintegrity.DataIntegrityService#getAllIntegrityChecks()
	 */
	public List<IntegrityCheck> getAllIntegrityChecks() throws APIException {
		return this.dao.getAllIntegrityChecks();
	}

	/**
	 * @see org.openmrs.module.dataintegrity.DataIntegrityService#getIntegrityCheck(Integer)
	 */
	public IntegrityCheck getIntegrityCheck(Integer checkId)
			throws APIException {
		if (checkId == null)
			return null;
		return this.dao.getIntegrityCheck(checkId);
	}

	/**
	 * @see org.openmrs.module.dataintegrity.DataIntegrityService#saveIntegrityCheck(IntegrityCheck)
	 */
	public IntegrityCheck saveIntegrityCheck(IntegrityCheck integrityCheck)
			throws APIException {
		return this.dao.saveIntegrityCheck(integrityCheck);
	}

	/**
	 * @see org.openmrs.module.dataintegrity.DataIntegrityService#deleteIntegrityCheck(IntegrityCheck)
	 */
	public void deleteIntegrityCheck(IntegrityCheck integrityCheck) {
		this.dao.deleteIntegrityCheck(integrityCheck);
	}

	/**
	 * @see org.openmrs.module.dataintegrity.DataIntegrityService#runIntegrityCheck(IntegrityCheck, String)
	 */
	public IntegrityCheckRun runIntegrityCheck(IntegrityCheck integrityCheck) throws Exception {
		DataIntegrityService service = Context.getService(DataIntegrityService.class);
		
		// do nothing if the check is null
		if (integrityCheck == null) {
			return null;
		}

		// pick the executor
		ICheckExecutor executor = getExecutors().get(
				integrityCheck.getFailureType());
		if (executor == null) {
			throw new APIException(
					"An executor was expected for integrity check failure type '"
					+ integrityCheck.getFailureType()
					+ "' but none was found.");
		}

		executor.initializeExecutor(integrityCheck);

		IntegrityCheckRun run = new IntegrityCheckRun();

		// time the query
		Long startTime = System.currentTimeMillis();
		executor.executeCheck();
		run.setDuration(System.currentTimeMillis() - startTime);

		// get the failed records
		QueryResults failedRecords = executor.getFailedRecords();
		
		// perform initial evaluation
		run.setTotalCount(failedRecords.size());
		run.setCheckPassed(executor.getCheckResult());

		// get the repair results if it failed
		if (!run.getCheckPassed() && StringUtils.hasText(integrityCheck.getResultsCode())) {
			// time the repair query
			startTime = System.currentTimeMillis();
			failedRecords = dao.getQueryResults(integrityCheck.getResultsCode());
			run.setDuration(run.getDuration() + System.currentTimeMillis() - startTime);
		}

		// update failed records
		this.addOrUpdateResultsForRun(integrityCheck, failedRecords, run);

		// re-evaluate pass/fail knowing how many records should be ignored
		run.setCheckPassed(
				executor.compare(
					Integer.valueOf(integrityCheck.getFailureThreshold()), 
					run.getTotalCount(), 
					integrityCheck.getFailureOperator()));
		
		// iterate over records and void those that did not show up this time
		this.voidResultsNotFoundInThisRun(integrityCheck, run);
		
		// set other stuff on the run
		run.setCreator(Context.getAuthenticatedUser());
		run.setDateCreated(new Date());
		
		// save (or update) the results
		integrityCheck.addRun(run);
		service.saveIntegrityCheck(integrityCheck);
		return run;
	}

	/**
	 * @see DataIntegrityService#getQueryResults(java.lang.String) 
	 */
	public QueryResults getQueryResults(String code) throws APIException {
		return getQueryResults(code, null);
	}

	/**
	 * @see DataIntegrityService#getQueryResults(java.lang.String, java.lang.Integer) 
	 */
	public QueryResults getQueryResults(String code, Integer limit) throws APIException {
		if (code == null) {
			return null;
		}

		if (!code.trim().toLowerCase().startsWith("select")) {
			throw new APIException("can not process SQL that does not begin with SELECT.");
		}

		return dao.getQueryResults(code, limit);
	}

	/**
	 * builds a cache of executors used for processing integrity checks
	 * 
	 * @return the cached map of executors
	 */
	private Map<String, ICheckExecutor> getExecutors() {
		if (executors == null) {
			executors = new HashMap<String, ICheckExecutor>();
			executors.put(DataIntegrityConstants.FAILURE_TYPE_COUNT,
					new CountCheckExecutor(this.getDataIntegrityDAO()));
		}
		return executors;
	}

	/**
	 * adds or updates results related to a given integrity check based on data from the given run
	 * 
	 * @param integrityCheck the related integrity check
	 * @param failedRecords the raw records found by running the check
	 * @param run the related integrity check run
	 */
	private void addOrUpdateResultsForRun(IntegrityCheck integrityCheck, QueryResults failedRecords, IntegrityCheckRun run) {
		// get column names for uniqueIdentifier (in order)
		UniqueIdentifierFinder finder = new UniqueIdentifierFinder(integrityCheck, failedRecords.getColumns());
		
		// loop through failed records
		for (Object[] record: failedRecords) {
			// generate uniqueIdentifier
			String uid = finder.findUniqueIdentifier(record);
			
			// check for result by uniqueIdentifier
			// TODO make this efficient, perhaps with a cached map or a DAO method
			IntegrityCheckResult result = null;
			Iterator<IntegrityCheckResult> iter = integrityCheck.getIntegrityCheckResults().iterator();
			if (iter != null)
				while (result == null && iter.hasNext()) {
					// set the next result
					result = iter.next();
					// check to see if the result's UID matches
					if (!(result != null && OpenmrsUtil.nullSafeEquals(result.getUniqueIdentifier(), uid)))
						// if not, reset it to null
						result = null;
				}
			
			// create a new result if it does not exist already
			if (result == null) {
				result = new IntegrityCheckResult();
				result.setUniqueIdentifier(uid);
				result.setFirstSeen(run);
				result.setIntegrityCheck(integrityCheck);
				result.setDateCreated(new Date());
				result.setCreator(Context.getAuthenticatedUser());
				result.setUuid(UUID.randomUUID().toString());
			}

			// update or set the status
			if (DataIntegrityConstants.RESULT_STATUS_IGNORED.equals(result.getStatus())) {
				// document that this record was ignored during this run
				run.setIgnoredCount(run.getIgnoredCount() == null ? 1 : run.getIgnoredCount() + 1);
				// remove the ignored record from the total
				run.setTotalCount(run.getTotalCount() == null ? -1 : run.getTotalCount() - 1);
			} else if (!DataIntegrityConstants.RESULT_STATUS_NEW.equals(result.getStatus())) {
				// if it was previously voided or did not exist, mark it as new
				result.setStatus(DataIntegrityConstants.RESULT_STATUS_NEW);
				run.setNewCount(run.getNewCount() == null ? 1 : run.getNewCount() + 1);
			}

			// update or set data and last seen run
			result.setData(new QueryResult(failedRecords.getColumns(), record));
			result.setLastSeen(run);
			
			// save it
			integrityCheck.addOrReplaceResult(result);
		}
	}

	/**
	 * @see DataIntegrityService#findResultForIntegrityCheckByUid(org.openmrs.module.dataintegrity.IntegrityCheck, java.lang.String) 
	 */
	public IntegrityCheckResult findResultForIntegrityCheckByUid(IntegrityCheck integrityCheck, String uid) {
		return dao.findResultForIntegrityCheckByUid(integrityCheck, uid);
	}

	/**
	 * sets result status to VOIDED for all results not found in referenced run
	 * 
	 * @param integrityCheck the integrity check with results to be voided
	 * @param run the referenced integrity check run
	 */
	private void voidResultsNotFoundInThisRun(IntegrityCheck integrityCheck, IntegrityCheckRun run) {
		for (IntegrityCheckResult result: integrityCheck.getIntegrityCheckResults())
			if (result != null && result.getLastSeen() != null && !result.getLastSeen().equals(run)
					&& !DataIntegrityConstants.RESULT_STATUS_VOIDED.equals(result.getStatus())
					&& !DataIntegrityConstants.RESULT_STATUS_IGNORED.equals(result.getStatus())) {
				result.setStatus(DataIntegrityConstants.RESULT_STATUS_VOIDED);
				run.setVoidedCount(run.getVoidedCount() == null ? 1 : run.getVoidedCount() + 1);
			}
	}

	/**
	 * @see DataIntegrityService#retireIntegrityCheck(org.openmrs.module.dataintegrity.IntegrityCheck, java.lang.String) 
	 */
	public void retireIntegrityCheck(IntegrityCheck check, String reason) {
		check.setRetired(true);
		check.setRetireReason(reason);
		Context.getService(DataIntegrityService.class).saveIntegrityCheck(check);
	}

	/**
	 * @see DataIntegrityService#unretireIntegrityCheck(org.openmrs.module.dataintegrity.IntegrityCheck) 
	 */
	public void unretireIntegrityCheck(IntegrityCheck check) {
		check.setRetired(false);
		Context.getService(DataIntegrityService.class).saveIntegrityCheck(check);
	}

	/**
	 * @see DataIntegrityService#getMostRecentRunsForAllChecks() 
	 */
	public List<IntegrityCheckRun> getMostRecentRunsForAllChecks() {
		List<IntegrityCheckRun> runs = new ArrayList<IntegrityCheckRun>();
		
		List<IntegrityCheck> checks = Context.getService(DataIntegrityService.class).getAllIntegrityChecks();
		for (IntegrityCheck check : checks) {
			IntegrityCheckRun run = dao.getMostRecentRunForCheck(check);
			if (run != null)
				runs.add(run);
		}
		
		return runs;
	}
	
	/**
	 * class used by methods in this service implementation to determine the unique identifier.
	 */
	private class UniqueIdentifierFinder {
		private List<Integer> indexes;
		
		/**
		 * initializes the class with indexes to use.
		 * 
		 * @param check the integrity check to base the indexes on
		 * @param columns a list of column names
		 */
		public UniqueIdentifierFinder(IntegrityCheck check, List<String> columns) {
			indexes = new ArrayList<Integer>();
			for (IntegrityCheckColumn column: check.getResultsColumns())
				if (column.getUsedInUid())
					indexes.add(columns.indexOf(column.getName()));
		}
		
		/**
		 * extracts the unique identifier based on selected UID columns
		 * 
		 * @param result the result to parse
		 * @return the UID as determined by the columns
		 */
		public String findUniqueIdentifier(Object[] result) {
			StringBuilder sb = new StringBuilder();
			try {
				for (Integer index: indexes) {
					sb.append(result[index].toString());
				}
			} catch (ArrayIndexOutOfBoundsException e) {
				throw new APIException("cannot find unique identifier; ", e);
			}
			return sb.toString();
		}
	}
}
