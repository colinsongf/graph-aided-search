/*
 * Copyright (c) 2013-2016 GraphAware
 *
 * This file is part of the GraphAware Framework.
 *
 * GraphAware Framework is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy of
 * the GNU General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.graphaware.integration.es.booster;

import com.graphaware.integration.es.GraphAidedSearchPlugin;
import com.graphaware.integration.es.IndexInfo;
import com.graphaware.integration.es.annotation.SearchBooster;
import com.graphaware.integration.es.domain.ExternalResult;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import org.codehaus.jackson.jaxrs.JacksonJsonProvider;
import org.codehaus.jackson.map.ObjectMapper;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;

import javax.ws.rs.core.MediaType;
import java.util.*;

import static com.graphaware.integration.es.domain.Constants.*;

@SearchBooster(name = "SearchResultCypherBooster")
public class SearchResultCypherBooster extends SearchResultExternalBooster {

    private final ESLogger logger;

    private String cypherQuery;
    private String scoreResultName;
    private String idResultName;

    public SearchResultCypherBooster(Settings settings, IndexInfo indexInfo) {
        super(settings, indexInfo);
        this.logger = Loggers.getLogger(GraphAidedSearchPlugin.INDEX_LOGGER_NAME, settings);
    }

    @Override
    protected void extendedParseRequest(Map<String, String>  extParams) {
        cypherQuery = extParams.get(QUERY);
        scoreResultName = extParams.get(SCORE_NAME) != null ? extParams.get(SCORE_NAME) : DEFAULT_SCORE_RESULT_NAME;
        idResultName = extParams.get(IDENTIFIER) != null ? extParams.get(IDENTIFIER) : DEFAULT_ID_RESULT_NAME;
        if (null == cypherQuery) {
            throw new RuntimeException("The Query Parameter cannot be null in gas-booster");
        }
    }

    @Override
    protected Map<String, ExternalResult> externalDoReorder(Set<String> keySet) {
        logger.debug("Query cypher for: " + keySet);
        Map<String, Float> res = executeCypher(getNeo4jHost(), keySet, cypherQuery);

        HashMap<String, ExternalResult> results = new HashMap<>();

        for (Map.Entry<String, Float> item : res.entrySet()) {
            results.put(item.getKey(), new ExternalResult(item.getKey(), item.getValue()));
        }
        return results;
    }

    //todo can this not be a query with parameters?
    protected Map<String, Float> executeCypher(String serverUrl, Set<String> resultKeySet, String... cypherStatements) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            stringBuilder.append("{\"statements\" : [");
            for (String statement : cypherStatements) {
                stringBuilder.append("{\"statement\" : \"").append(statement).append("\"").append(",");
                stringBuilder.append("\"parameters\":").append("{\"ids\":").append(ObjectMapper.class.newInstance().writeValueAsString(resultKeySet)).append("}").append("}");
            }

            stringBuilder.append("]}");
        } catch (Exception e) {
            throw new RuntimeException("Unable to build the Cypher query : " + e.getMessage());
        }

        while (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }

        return post(serverUrl + CYPHER_ENDPOINT, stringBuilder.toString());
    }

    protected Map<String, Float> post(String url, String json) {
        ClientConfig cfg = new DefaultClientConfig();
        cfg.getClasses().add(JacksonJsonProvider.class);
        WebResource resource = Client.create(cfg).resource(url);
        ClientResponse response = resource
                .accept(MediaType.APPLICATION_JSON)
                .type(MediaType.APPLICATION_JSON)
                .entity(json)
                .post(ClientResponse.class);
        GenericType<Map<String, Object>> type = new GenericType<Map<String, Object>>() {
        };
        Map<String, Object> results = response.getEntity(type);
        try {
            System.out.println(ObjectMapper.class.newInstance().writeValueAsString(results)); //todo log instead of System.out?
        } catch (Exception e) {
            //
        }
        @SuppressWarnings("unchecked")
        ArrayList<HashMap<String, Object>> errors = (ArrayList) results.get(ERRORS);
        if (errors.size() > 0) {
            throw new RuntimeException("Cypher Execution Error, message is : " + errors.get(0).toString());
        }

        Map res = (Map) ((ArrayList) results.get(RESULTS)).get(0);
        ArrayList<LinkedHashMap> rows = (ArrayList) res.get(DATA);
        List<String> columns = (List) res.get(COLUMNS);
        response.close();
        int k = 0;
        Map<String, Integer> columnsMap = new HashMap<>();
        for (String c : columns) {
            columnsMap.put(c, k);
            ++k;
        }
        Map<String, Float> resultRows = new HashMap<>();
        for (LinkedHashMap r : rows) {
            ArrayList row = (ArrayList) r.get(ROW);
            String key = String.valueOf(row.get(columnsMap.get(idResultName)));
            float value = Float.parseFloat(String.valueOf(row.get(columnsMap.get(scoreResultName))));
            resultRows.put(key, value);
        }
        return resultRows;
    }
}