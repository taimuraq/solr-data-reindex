package com.vroozi.service;

import com.vroozi.model.FailedRecord;
import com.vroozi.model.MaterialGroupMapping;
import com.vroozi.model.ProcessState;
import com.vroozi.model.ProcessType;
import com.vroozi.model.Result;
import com.vroozi.model.SolrReindexProcess;
import com.vroozi.repository.MaterialGroupDao;
import com.vroozi.repository.SolrReindexProcessRepository;
import com.vroozi.util.CategoryMatcher;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SolrDataReindexService {

  private static final Logger LOGGER = LoggerFactory.getLogger(SolrDataReindexService.class);

  @Value("${solr.url}")
  private String solrUrl;

  @Value("${records.per.page}")
  int recordsPerPage;

  @Autowired
  private MaterialGroupDao materialGroupDao;

  @Autowired
  private SolrReindexProcessRepository solrReindexProcessRepository;

  /**
   * Reindexes solr documents for given unitId
   *
   * @param unitId unique identifier for buying organization
   */

  public SolrReindexProcess submitSolrReindexingProcess(Integer unitId) {
    SolrReindexProcess solrReindexProcess = new SolrReindexProcess(unitId);
    return solrReindexProcessRepository.save(solrReindexProcess);
  }

  @Scheduled(cron = "${solr.reindexing.cron.expression}")
  public void processSolrReindexing() {
    SolrReindexProcess solrReindexProcess = solrReindexProcessRepository
        .findByProcessTypeAndProcessState(ProcessType.REINDEX, ProcessState.UNPROCESSED);
    if (solrReindexProcess != null) {
      HttpSolrClient solr = new HttpSolrClient.Builder(solrUrl).build();
      solr.setParser(new XMLResponseParser());
      solrReindexProcess.setProcessState(ProcessState.PROCESSING);
      if (solrReindexProcess.getUnitId() != null) {
        reindex(solr, solrReindexProcess, solrReindexProcess.getUnitId());
      } else {
        materialGroupDao.findAllUnitIds()
            .forEach(unitId -> reindex(solr, solrReindexProcess, unitId));
      }
      solrReindexProcess.setProcessState(ProcessState.COMPLETED);
    } else {
      LOGGER.debug("Nothing to Process!");
    }
  }

  /* Retries the failed batches */
  @Scheduled(cron = "${solr.retry.cron.expression}")
  public void processFailedBatches() {
    SolrReindexProcess solrReindexProcess = solrReindexProcessRepository
        .findByProcessTypeAndProcessState(ProcessType.RETRY_FAILED, ProcessState.UNPROCESSED);

    if (solrReindexProcess != null) {
      int unitId = solrReindexProcess.getUnitId();
      if (!solrReindexProcess.getFailedRecords().isEmpty()) {
        HttpSolrClient solr = new HttpSolrClient.Builder(solrUrl).build();
        solr.setParser(new XMLResponseParser());

        /* Fetching list of materialGroupMapping from db */
        List<MaterialGroupMapping> materialGroupMappingList = materialGroupDao
            .findByUnitIdOrderByCatalogCategoryCodeDesc(unitId);
        solrReindexProcess.setProcessState(ProcessState.PROCESSING);
        solrReindexProcess.setStartTime(new Date());
        solrReindexProcessRepository.save(solrReindexProcess);

        /* Processing failed batches against a unitId */
        solrReindexProcess.getFailedRecords().forEach(failedRecord -> {
          Result result = new Result(unitId);
          try {
            QueryResponse response = getQueryResponse(solr, failedRecord.getStart(),
                failedRecord.getRecordsPerPage(), unitId);
            List<SolrInputDocument> solrInputDocumentList = processSolrInputDocuments(unitId,
                response, materialGroupMappingList, result);
            /* Adding solrInputDocument to solr in batches, log the batches in db if solr.commit fails */
            commitSolrBatch(unitId, solr, failedRecord.getStart(), failedRecord.getRecordsPerPage(),
                solrInputDocumentList);
            solrReindexProcess.getResults().add(result);
          } catch (Exception e) {
            LOGGER.error("Exception occurred while retrying failed records ", e);
          }
        });
      }
      solrReindexProcess.setEndTime(new Date());
      solrReindexProcess.setProcessState(ProcessState.COMPLETED);
      solrReindexProcessRepository.save(solrReindexProcess);
    }
  }

  /* reindexes solr data for catalogItems against given unitId */
  public void reindex(HttpSolrClient solr, SolrReindexProcess solrReindexProcess, Integer unitId) {
    LOGGER.info("Going to start reindex data for unitId: {}", unitId);

    solrReindexProcess.setProcessState(ProcessState.PROCESSING);
    solrReindexProcessRepository.save(solrReindexProcess);

    /* Fetching list of materialGroupMapping from db */
    List<MaterialGroupMapping> materialGroupMappingList = materialGroupDao
        .findByUnitIdOrderByCatalogCategoryCodeDesc(unitId);

    int start = 0;
    int recordsProcessed = 0;
    int totalPage = getTotalPage(unitId, solr);
    QueryResponse response;
    Result result = new Result(unitId);

    for (int i = 0; i < totalPage; i++) {
      try {
        response = getQueryResponse(solr, start, recordsPerPage, unitId);
        List<SolrInputDocument> solrInputDocumentList = processSolrInputDocuments(unitId, response,
            materialGroupMappingList, result);
        recordsProcessed += solrInputDocumentList.size();

        /* Adding solrInputDocument to solr in batches */
        commitSolrBatch(unitId, solr, start, recordsPerPage, solrInputDocumentList);
        LOGGER.info("Records processed from {} to {}.", start, recordsProcessed);
        start = recordsProcessed;
      } catch (Exception e) {
        LOGGER.error("Exception occurred while reindexing ", e);
      }
    }
    solrReindexProcess.getResults().add(result);
    solrReindexProcess.setProcessState(ProcessState.COMPLETED);
    solrReindexProcessRepository.save(solrReindexProcess);
    LOGGER.info("Reindexing completed for unitId: {}", unitId);
  }

  private List<SolrInputDocument> processSolrInputDocuments(Integer unitId, QueryResponse response,
      List<MaterialGroupMapping> materialGroupMappingList, Result result)
      throws UnsupportedEncodingException {
    List<SolrDocument> solrDocumentList = response.getResults();
    List<SolrInputDocument> solrInputDocumentList = new ArrayList<>();
      /* Iterating over solrInputDocuments to convert it to solrInputDocuments and add the
         new fields like vendor_name_lowercase, mat_group_info and mat_group_label */
    for (SolrDocument doc : solrDocumentList) {
      SolrInputDocument solrInputDocument = toSolrInputDocument(doc);
      removeCopyFields(solrInputDocument);
      String solrDocumentId = solrInputDocument.get("id").getValue().toString();

      boolean recordUpdated = setMaterialGroupData(unitId, result.getErrors(),
          materialGroupMappingList,
          solrInputDocument, solrDocumentId);

      recordUpdated = setVendorData(recordUpdated, solrInputDocument);

      if (recordUpdated) {
        result.getIdsUpdated().add(solrDocumentId);
      }

      solrInputDocumentList.add(solrInputDocument);
    }
    return solrInputDocumentList;
  }

  private void commitSolrBatch(Integer unitId, HttpSolrClient solr, int start, int recordsPerPage,
      List<SolrInputDocument> solrInputDocumentList) {
    if (!solrInputDocumentList.isEmpty()) {
      try {
        solr.add(solrInputDocumentList);
        solr.commit();
      } catch (Exception e) {
        logFailedBatch(unitId, start, recordsPerPage, ExceptionUtils.getStackTrace(e));
        LOGGER.error("Error occurred while comitting the batch", e);
      }
    }
  }

  private void logFailedBatch(Integer unitId, int start, int recordsPerPage, String stackTrace) {
    SolrReindexProcess failedSolrReindexProcess = solrReindexProcessRepository
        .findByProcessTypeAndProcessStateAndUnitId(ProcessType.RETRY_FAILED,
            ProcessState.UNPROCESSED, unitId);
    if (failedSolrReindexProcess == null) {
      failedSolrReindexProcess = new SolrReindexProcess(unitId);
      failedSolrReindexProcess.setProcessType(ProcessType.RETRY_FAILED);
      failedSolrReindexProcess.setProcessState(ProcessState.UNPROCESSED);
    }
    failedSolrReindexProcess.getFailedRecords()
        .add(new FailedRecord(start, recordsPerPage, stackTrace));
    solrReindexProcessRepository.save(failedSolrReindexProcess);
  }

  /**
   * Sets vendor_name_lowercase field if there is existing vendor_name field present
   */

  private boolean setVendorData(boolean recordUpdated, SolrInputDocument solrInputDocument) {
    String vendorName = null;
    if (solrInputDocument.get("vendor_name") != null) {
      vendorName = solrInputDocument.get("vendor_name").getValue().toString();
    }

    if (StringUtils.isNotBlank(vendorName)) {
      solrInputDocument.removeField("vendor_name_lowercase");
      solrInputDocument.addField("vendor_name_lowercase", vendorName);
      recordUpdated = true;
    }
    return recordUpdated;
  }

  /**
   * Sets New Material Group Data in solrInputDocument if there is existing data, else adds an error
   * regarding mat group not found for that particular solrInputDocument.
   */
  private boolean setMaterialGroupData(int unitId, List<String> errors,
      List<MaterialGroupMapping> materialGroupMappings,
      SolrInputDocument solrInputDocument, String solrDocumentId)
      throws UnsupportedEncodingException {
    boolean updated = false;
    String matGroupLabel;
    String matGroupInfo;
    String matGroupCode = null;
    if (solrInputDocument.get("mat_group") != null) {
      matGroupCode = solrInputDocument.get("mat_group").getValue().toString();
    }
    if (StringUtils.isNotBlank(matGroupCode)) {
      MaterialGroupMapping materialGroupMapping = CategoryMatcher
          .findMaterialGroupMappingAgainstItemMatGroup(materialGroupMappings, matGroupCode);
      if (materialGroupMapping != null) {
        matGroupLabel = materialGroupMapping.getCompanyLabel();
        if (StringUtils.isNotBlank(matGroupLabel)) {
          matGroupInfo = materialGroupMapping.getCompanyCategoryCode() + "|||" + URLEncoder
              .encode(matGroupLabel, "UTF-8");
          solrInputDocument.removeField("mat_group_label");
          solrInputDocument.removeField("mat_group_info");
          solrInputDocument.addField("mat_group_label", matGroupLabel);
          solrInputDocument.addField("mat_group_info", matGroupInfo);
          updated = true;
        } else {
          errors.add(String
              .format("MatGroupLabel not found for unitId: %d, id: %s, matGroupCode: %s", unitId,
                  solrDocumentId, matGroupCode));
          LOGGER.error("MatGroupLabel not found for unitId: {}, id: {}, matGroupCode: {}", unitId,
              solrDocumentId, matGroupCode);
        }
      }
    }
    return updated;
  }

  /**
   * Gets totalPages available in solr collection
   *
   * @param unitId unique identifier for buying organization
   * @param solr solrClient for executing solr query
   */
  private int getTotalPage(int unitId, HttpSolrClient solr) {
    QueryResponse response = null;
    int totalPages = 0;
    try {
      response = getQueryResponse(solr, 0, 1, unitId);
      totalPages = (int) Math.ceil(((double) response.getResults().getNumFound()) / recordsPerPage);
      LOGGER.info("total {} pages to process for unitId: {}", totalPages, unitId);
    } catch (Exception e) {
      LOGGER.error("Error occur while getting total pages for unitId: {}", unitId, e);
    }
    return totalPages;
  }

  /**
   * Removes copy fields from solrInputDocument, else inserting it will create list of values for
   * single copy field
   *
   * @param solrInputDocument solrInputDocument which will be used for saving data in solr
   */
  private void removeCopyFields(SolrInputDocument solrInputDocument) {
    solrInputDocument.removeField("a_auto");
    solrInputDocument.removeField("a_autoPhrase");
    solrInputDocument.removeField("manufact_mat_auto");
    solrInputDocument.removeField("manufact_mat_autoPhrase");
    solrInputDocument.removeField("ext_quote_id_auto");
    solrInputDocument.removeField("ext_quote_id_autoPhrase");
    solrInputDocument.removeField("bundle_no_auto");
    solrInputDocument.removeField("bundle_no_autoPhrase");
    solrInputDocument.removeField("a_spell");
    solrInputDocument.removeField("a_spellPhrase");
    solrInputDocument.removeField("text");
    solrInputDocument.removeField("descSuggestions");
    solrInputDocument.removeField("customFieldsSearchableSuggestions");
    solrInputDocument.removeField("matGroupLabelSuggestions");
    solrInputDocument.removeField("vendorNameSuggestions");
    solrInputDocument.removeField("suggestions");
    solrInputDocument.removeField("suggestionsedge");
  }

  /**
   * Returns QueryResponse after constructing query and executing it
   *
   * @param solr solrClient instance
   * @param start start row from where to start fetching record
   * @param rows no of records to fetch
   * @param unitId unique identifier for buying organization
   */
  private QueryResponse getQueryResponse(HttpSolrClient solr, int start, int rows, int unitId)
      throws IOException, SolrServerException {
    SolrQuery query = new SolrQuery();
    query.set(CommonParams.Q, "unitid:" + unitId);
    query.set(CommonParams.START, start);
    query.set(CommonParams.ROWS, rows);
    return solr.query(query);
  }

  /**
   * Converting SolrDocument to SolrInputDocument as SolrInputDocument can be used to commit docs in
   * solr.
   *
   * @param document SolrDocument received after quering solr
   * @return SolrInputDocument
   */
  private SolrInputDocument toSolrInputDocument(SolrDocument document) {
    SolrInputDocument solrInputDocument = new SolrInputDocument();
    for (String name : document.getFieldNames()) {
      solrInputDocument.addField(name, document.getFieldValue(name));
    }
    return solrInputDocument;
  }
}