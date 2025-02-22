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

package org.apache.shardingsphere.data.pipeline.core.ingest.dumper;

import com.google.common.base.Strings;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.common.execute.AbstractPipelineLifecycleRunnable;
import org.apache.shardingsphere.data.pipeline.core.ingest.channel.PipelineChannel;
import org.apache.shardingsphere.data.pipeline.core.ingest.dumper.context.InventoryDumperContext;
import org.apache.shardingsphere.data.pipeline.core.ingest.position.IngestPosition;
import org.apache.shardingsphere.data.pipeline.core.ingest.record.Column;
import org.apache.shardingsphere.data.pipeline.core.ingest.record.DataRecord;
import org.apache.shardingsphere.data.pipeline.core.ingest.record.FinishedRecord;
import org.apache.shardingsphere.data.pipeline.core.ingest.record.Record;
import org.apache.shardingsphere.data.pipeline.common.job.JobOperationType;
import org.apache.shardingsphere.data.pipeline.common.metadata.loader.PipelineTableMetaDataLoader;
import org.apache.shardingsphere.data.pipeline.common.metadata.model.PipelineColumnMetaData;
import org.apache.shardingsphere.data.pipeline.common.metadata.model.PipelineTableMetaData;
import org.apache.shardingsphere.data.pipeline.common.ingest.IngestDataChangeType;
import org.apache.shardingsphere.data.pipeline.common.ingest.position.FinishedPosition;
import org.apache.shardingsphere.data.pipeline.common.ingest.position.PlaceholderPosition;
import org.apache.shardingsphere.data.pipeline.common.ingest.position.pk.PrimaryKeyPosition;
import org.apache.shardingsphere.data.pipeline.common.ingest.position.pk.PrimaryKeyPositionFactory;
import org.apache.shardingsphere.data.pipeline.common.query.JDBCStreamQueryBuilder;
import org.apache.shardingsphere.data.pipeline.common.sqlbuilder.PipelineInventoryDumpSQLBuilder;
import org.apache.shardingsphere.data.pipeline.common.util.PipelineJdbcUtils;
import org.apache.shardingsphere.data.pipeline.core.exception.IngestException;
import org.apache.shardingsphere.data.pipeline.core.exception.param.PipelineInvalidParameterException;
import org.apache.shardingsphere.data.pipeline.common.spi.algorithm.JobRateLimitAlgorithm;
import org.apache.shardingsphere.infra.database.core.type.DatabaseType;
import org.apache.shardingsphere.infra.database.mysql.type.MySQLDatabaseType;
import org.apache.shardingsphere.infra.exception.core.ShardingSpherePreconditions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Inventory dumper.
 */
@Slf4j
public final class InventoryDumper extends AbstractPipelineLifecycleRunnable implements Dumper {
    
    @Getter(AccessLevel.PROTECTED)
    private final InventoryDumperContext dumperContext;
    
    private final PipelineChannel channel;
    
    private final DataSource dataSource;
    
    private final PipelineInventoryDumpSQLBuilder inventoryDumpSQLBuilder;
    
    private final ColumnValueReaderEngine columnValueReaderEngine;
    
    private final PipelineTableMetaDataLoader metaDataLoader;
    
    private final AtomicReference<Statement> dumpStatement = new AtomicReference<>();
    
    public InventoryDumper(final InventoryDumperContext dumperContext, final PipelineChannel channel, final DataSource dataSource, final PipelineTableMetaDataLoader metaDataLoader) {
        this.dumperContext = dumperContext;
        this.channel = channel;
        this.dataSource = dataSource;
        DatabaseType databaseType = dumperContext.getCommonContext().getDataSourceConfig().getDatabaseType();
        inventoryDumpSQLBuilder = new PipelineInventoryDumpSQLBuilder(databaseType);
        columnValueReaderEngine = new ColumnValueReaderEngine(databaseType);
        this.metaDataLoader = metaDataLoader;
    }
    
    @Override
    protected void runBlocking() {
        IngestPosition position = dumperContext.getCommonContext().getPosition();
        if (position instanceof FinishedPosition) {
            log.info("Ignored because of already finished.");
            return;
        }
        PipelineTableMetaData tableMetaData = metaDataLoader.getTableMetaData(
                dumperContext.getCommonContext().getTableAndSchemaNameMapper().getSchemaName(dumperContext.getLogicTableName()), dumperContext.getActualTableName());
        try (Connection connection = dataSource.getConnection()) {
            dump(tableMetaData, connection);
        } catch (final SQLException ex) {
            log.error("Inventory dump, ex caught, msg={}.", ex.getMessage());
            throw new IngestException("Inventory dump failed on " + dumperContext.getActualTableName(), ex);
        }
    }
    
    @SuppressWarnings("MagicConstant")
    private void dump(final PipelineTableMetaData tableMetaData, final Connection connection) throws SQLException {
        int batchSize = dumperContext.getBatchSize();
        DatabaseType databaseType = dumperContext.getCommonContext().getDataSourceConfig().getDatabaseType();
        if (null != dumperContext.getTransactionIsolation()) {
            connection.setTransactionIsolation(dumperContext.getTransactionIsolation());
        }
        try (PreparedStatement preparedStatement = JDBCStreamQueryBuilder.build(databaseType, connection, buildInventoryDumpSQL())) {
            dumpStatement.set(preparedStatement);
            if (!(databaseType instanceof MySQLDatabaseType)) {
                preparedStatement.setFetchSize(batchSize);
            }
            setParameters(preparedStatement);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                int rowCount = 0;
                JobRateLimitAlgorithm rateLimitAlgorithm = dumperContext.getRateLimitAlgorithm();
                ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
                List<Record> dataRecords = new LinkedList<>();
                while (resultSet.next()) {
                    if (dataRecords.size() >= batchSize) {
                        channel.pushRecords(dataRecords);
                        dataRecords = new LinkedList<>();
                    }
                    dataRecords.add(loadDataRecord(resultSet, resultSetMetaData, tableMetaData));
                    ++rowCount;
                    if (!isRunning()) {
                        log.info("Broke because of inventory dump is not running.");
                        break;
                    }
                    if (null != rateLimitAlgorithm && 0 == rowCount % batchSize) {
                        rateLimitAlgorithm.intercept(JobOperationType.SELECT, 1);
                    }
                }
                dataRecords.add(new FinishedRecord(new FinishedPosition()));
                channel.pushRecords(dataRecords);
                dumpStatement.set(null);
                log.info("Inventory dump done, rowCount={}", rowCount);
            }
        }
    }
    
    private String buildInventoryDumpSQL() {
        if (!Strings.isNullOrEmpty(dumperContext.getQuerySQL())) {
            return dumperContext.getQuerySQL();
        }
        String schemaName = dumperContext.getCommonContext().getTableAndSchemaNameMapper().getSchemaName(dumperContext.getLogicTableName());
        if (!dumperContext.hasUniqueKey()) {
            return inventoryDumpSQLBuilder.buildFetchAllSQL(schemaName, dumperContext.getActualTableName());
        }
        PrimaryKeyPosition<?> primaryKeyPosition = (PrimaryKeyPosition<?>) dumperContext.getCommonContext().getPosition();
        PipelineColumnMetaData firstColumn = dumperContext.getUniqueKeyColumns().get(0);
        Collection<String> columnNames = Collections.singleton("*");
        if (PipelineJdbcUtils.isIntegerColumn(firstColumn.getDataType()) || PipelineJdbcUtils.isStringColumn(firstColumn.getDataType())) {
            if (null != primaryKeyPosition.getBeginValue() && null != primaryKeyPosition.getEndValue()) {
                return inventoryDumpSQLBuilder.buildDivisibleSQL(schemaName, dumperContext.getActualTableName(), columnNames, firstColumn.getName());
            }
            if (null != primaryKeyPosition.getBeginValue() && null == primaryKeyPosition.getEndValue()) {
                return inventoryDumpSQLBuilder.buildUnlimitedDivisibleSQL(schemaName, dumperContext.getActualTableName(), columnNames, firstColumn.getName());
            }
        }
        return inventoryDumpSQLBuilder.buildIndivisibleSQL(schemaName, dumperContext.getActualTableName(), columnNames, firstColumn.getName());
    }
    
    private void setParameters(final PreparedStatement preparedStatement) throws SQLException {
        if (!dumperContext.hasUniqueKey()) {
            return;
        }
        PipelineColumnMetaData firstColumn = dumperContext.getUniqueKeyColumns().get(0);
        PrimaryKeyPosition<?> position = (PrimaryKeyPosition<?>) dumperContext.getCommonContext().getPosition();
        if (PipelineJdbcUtils.isIntegerColumn(firstColumn.getDataType()) && null != position.getBeginValue() && null != position.getEndValue()) {
            preparedStatement.setObject(1, position.getBeginValue());
            preparedStatement.setObject(2, position.getEndValue());
            return;
        }
        if (PipelineJdbcUtils.isStringColumn(firstColumn.getDataType())) {
            if (null != position.getBeginValue()) {
                preparedStatement.setObject(1, position.getBeginValue());
            }
            if (null != position.getEndValue()) {
                preparedStatement.setObject(2, position.getEndValue());
            }
        }
    }
    
    private DataRecord loadDataRecord(final ResultSet resultSet, final ResultSetMetaData resultSetMetaData, final PipelineTableMetaData tableMetaData) throws SQLException {
        int columnCount = resultSetMetaData.getColumnCount();
        DataRecord result = new DataRecord(IngestDataChangeType.INSERT, dumperContext.getLogicTableName(), newPosition(resultSet), columnCount);
        List<String> insertColumnNames = Optional.ofNullable(dumperContext.getInsertColumnNames()).orElse(Collections.emptyList());
        ShardingSpherePreconditions.checkState(insertColumnNames.isEmpty() || insertColumnNames.size() == resultSetMetaData.getColumnCount(),
                () -> new PipelineInvalidParameterException("Insert colum names count not equals ResultSet column count"));
        for (int i = 1; i <= columnCount; i++) {
            String columnName = insertColumnNames.isEmpty() ? resultSetMetaData.getColumnName(i) : insertColumnNames.get(i - 1);
            ShardingSpherePreconditions.checkNotNull(tableMetaData.getColumnMetaData(columnName), () -> new PipelineInvalidParameterException(String.format("Column name is %s", columnName)));
            boolean isUniqueKey = tableMetaData.getColumnMetaData(columnName).isUniqueKey();
            result.addColumn(new Column(columnName, columnValueReaderEngine.read(resultSet, resultSetMetaData, i), true, isUniqueKey));
        }
        return result;
    }
    
    private IngestPosition newPosition(final ResultSet resultSet) throws SQLException {
        return dumperContext.hasUniqueKey()
                ? PrimaryKeyPositionFactory.newInstance(
                        resultSet.getObject(dumperContext.getUniqueKeyColumns().get(0).getName()), ((PrimaryKeyPosition<?>) dumperContext.getCommonContext().getPosition()).getEndValue())
                : new PlaceholderPosition();
    }
    
    @Override
    protected void doStop() {
        PipelineJdbcUtils.cancelStatement(dumpStatement.get());
    }
}
