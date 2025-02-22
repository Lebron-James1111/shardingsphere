/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.data.pipeline.common.datasource;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.api.PipelineDataSourceConfiguration;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default pipeline data source manager.
 */
@Slf4j
public final class DefaultPipelineDataSourceManager implements PipelineDataSourceManager {
    
    private final Map<PipelineDataSourceConfiguration, PipelineDataSourceWrapper> cachedDataSources = new ConcurrentHashMap<>();
    
    @Override
    public PipelineDataSourceWrapper getDataSource(final PipelineDataSourceConfiguration dataSourceConfig) {
        PipelineDataSourceWrapper result = cachedDataSources.get(dataSourceConfig);
        if (null != result) {
            return result;
        }
        synchronized (cachedDataSources) {
            result = cachedDataSources.get(dataSourceConfig);
            if (null != result) {
                if (!result.isClosed()) {
                    return result;
                } else {
                    log.warn("{} is already closed, create again", result);
                }
            }
            result = PipelineDataSourceFactory.newInstance(dataSourceConfig);
            cachedDataSources.put(dataSourceConfig, result);
            return result;
        }
    }
    
    @Override
    public void close() {
        for (PipelineDataSourceWrapper each : cachedDataSources.values()) {
            if (each.isClosed()) {
                continue;
            }
            try {
                each.close();
            } catch (final SQLException ex) {
                log.error("An exception occurred while closing the data source", ex);
            }
        }
        cachedDataSources.clear();
    }
}
