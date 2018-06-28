/**
 * Copyright (C) 2018 Expedia Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.Iterator;
import org.json.JSONObject;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.text.SimpleDateFormat;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.MetaStoreEventListener;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.events.*;

import com.amazonaws.services.glue.AWSGlue;
import com.amazonaws.services.glue.AWSGlueClientBuilder;
import com.amazonaws.services.glue.model.EntityNotFoundException;
import com.amazonaws.services.glue.model.GetDatabaseRequest;
import com.amazonaws.services.glue.model.GetTableRequest;

import com.amazonaws.services.glue.model.*;

public class ApiaryGlueSync extends MetaStoreEventListener  {
    private static AWSGlue glueClient;

    public ApiaryGlueSync(Configuration config) {
        super(config);
        System.out.println(" ApiaryGlueSync created ");
        glueClient = AWSGlueClientBuilder.standard().withRegion(System.getenv("AWS_REGION")).build();
    }

    @Override
    public void onCreateTable(CreateTableEvent event) throws MetaException {
	if(event.getStatus() == false) return;
        Table table = event.getTable();
        CreateTableRequest createTableRequest = new CreateTableRequest().withTableInput(transformTable(table)).withDatabaseName(glueDbName(table));
        glueClient.createTable(createTableRequest);
        System.out.println(table + " table created in glue catalog.");
    }

    @Override
    public void onDropTable(DropTableEvent event) throws MetaException {
	if(event.getStatus() == false) return;
        Table table = event.getTable();
        DeleteTableRequest deleteTableRequest = new DeleteTableRequest().withName(table.getTableName()).withDatabaseName(glueDbName(table));
        glueClient.deleteTable(deleteTableRequest);
        System.out.println(table + " table deleted from glue catalog.");
    }

    @Override
    public void onAlterTable(AlterTableEvent event) throws MetaException {
	if(event.getStatus() == false) return;
	Table table = event.getNewTable();
        UpdateTableRequest updateTableRequest = new UpdateTableRequest().withTableInput(transformTable(table)).withDatabaseName(glueDbName(table));
        glueClient.updateTable(updateTableRequest);
        System.out.println(table + " table updated in glue catalog.");
    }

    @Override
    public void onAddPartition(AddPartitionEvent event) throws MetaException {
	if(event.getStatus() == false) return;
	Table table = event.getTable();
	Iterator<Partition> partitions = event.getPartitionIterator();
        while(partitions.hasNext())
        {
            Partition partition = partitions.next();
            CreatePartitionRequest createPartitionRequest = new CreatePartitionRequest()
                       .withPartitionInput(transformPartition(partition)).withDatabaseName(glueDbName(table)).withTableName(table.getTableName());
            glueClient.createPartition(createPartitionRequest);
        }
    }

    @Override
    public void onDropPartition(DropPartitionEvent event) throws MetaException {
	if(event.getStatus() == false) return;
	Table table = event.getTable();
	Iterator<Partition> partitions = event.getPartitionIterator();
        while(partitions.hasNext())
        {
            Partition partition = partitions.next();
            DeletePartitionRequest deletePartitionRequest = new DeletePartitionRequest()
                       .withPartitionValues(transformPartition(partition).getValues()).withDatabaseName(glueDbName(table)).withTableName(table.getTableName());
            glueClient.deletePartition(deletePartitionRequest);
        }
    }

    @Override
    public void onAlterPartition(AlterPartitionEvent event) throws MetaException {
	if(event.getStatus() == false) return;
        Table table = event.getTable();
        Partition partition = event.getNewPartition();
        UpdatePartitionRequest updatePartitionRequest = new UpdatePartitionRequest().withPartitionInput(transformPartition(partition)).withDatabaseName(glueDbName(table)).withTableName(table.getTableName());
        glueClient.updatePartition(updatePartitionRequest);
    }

    TableInput transformTable(final Table tableName) {

        final Date date = getTableDate(tableName.getLastAccessTime());

        final List<Column> partKeyCollection = getColumns(tableName.getPartitionKeys());

        final org.apache.hadoop.hive.metastore.api.StorageDescriptor storageDescriptor = tableName.getSd();
        final List<Column> colCollection = getColumns(storageDescriptor.getCols());

        final SerDeInfo glueSerde = new SerDeInfo().withName(storageDescriptor.getSerdeInfo().getName())
                .withParameters(storageDescriptor.getSerdeInfo().getParameters())
                .withSerializationLibrary(storageDescriptor.getSerdeInfo().getSerializationLib());


        final List<Order> orderCollection = getTransformedOrders(storageDescriptor.getSortCols());

        final StorageDescriptor sd = new StorageDescriptor()
                .withBucketColumns(storageDescriptor.getBucketCols())
                .withColumns(colCollection)
                .withCompressed(storageDescriptor.isCompressed())
                .withInputFormat(storageDescriptor.getInputFormat())
                .withLocation(storageDescriptor.getLocation())
                .withNumberOfBuckets(storageDescriptor.getNumBuckets())
                .withOutputFormat(storageDescriptor.getOutputFormat())
                .withParameters(storageDescriptor.getParameters())
                .withSerdeInfo(glueSerde)
                .withSortColumns(orderCollection)
                .withStoredAsSubDirectories(storageDescriptor.isStoredAsSubDirectories());

        final TableInput tableInput = new TableInput()
                .withName(tableName.getTableName())
                .withLastAccessTime(date)
                .withOwner(tableName.getOwner())
                .withParameters(tableName.getParameters())
                .withPartitionKeys(partKeyCollection)
                .withRetention(tableName.getRetention())
                .withStorageDescriptor(sd)
                .withTableType(tableName.getTableType());


        return tableInput;

    }

    PartitionInput transformPartition(final Partition partition) {

        final Date date = getTableDate(partition.getLastAccessTime());

        final org.apache.hadoop.hive.metastore.api.StorageDescriptor storageDescriptor = partition.getSd();
        final Collection<Column> colCollection = getColumns(storageDescriptor.getCols());

        final SerDeInfo glueSerde = new SerDeInfo().withName(storageDescriptor.getSerdeInfo().getName())
                .withParameters(storageDescriptor.getSerdeInfo().getParameters())
                .withSerializationLibrary(storageDescriptor.getSerdeInfo().getSerializationLib());

        final List<Order> orderCollection = getTransformedOrders(storageDescriptor.getSortCols());

        final StorageDescriptor sd = new StorageDescriptor()
                .withBucketColumns(storageDescriptor.getBucketCols())
                .withColumns(colCollection)
                .withCompressed(storageDescriptor.isCompressed())
                .withInputFormat(storageDescriptor.getInputFormat())
                .withLocation(storageDescriptor.getLocation())
                .withNumberOfBuckets(storageDescriptor.getNumBuckets())
                .withOutputFormat(storageDescriptor.getOutputFormat())
                .withParameters(storageDescriptor.getParameters())
                .withSerdeInfo(glueSerde)
                .withSortColumns(orderCollection)
                .withStoredAsSubDirectories(storageDescriptor.isStoredAsSubDirectories());

        final PartitionInput partitionInput = new PartitionInput()
                .withLastAccessTime(date)
                .withParameters(partition.getParameters())
                .withStorageDescriptor(sd)
                .withValues(partition.getValues());

        return partitionInput;
    }

    private List<Order> getTransformedOrders(final List<org.apache.hadoop.hive.metastore.api.Order> hiveOrders) {
        final List<Order> transformedOrders = new ArrayList<>();
        for (final org.apache.hadoop.hive.metastore.api.Order hiveOrder : hiveOrders) {
            final Order order = new Order().withSortOrder(hiveOrder.getOrder()).withColumn(hiveOrder.getCol());
            transformedOrders.add(order);
        }
        return transformedOrders;
    }

    private List<Column> getColumns(final List<FieldSchema> colList) {
        final List<Column> colCollection = new ArrayList<>();

        for (final FieldSchema fieldSchema : colList) {
            final Column col = new Column().withName(fieldSchema.getName()).withType(fieldSchema.getType())
                    .withComment(fieldSchema.getComment());

            colCollection.add(col);
        }
        return colCollection;
    }

    private Date getTableDate(final Integer lastAccessTime) {
        if (lastAccessTime == 0) {
            return null;
        }
        try {

            final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            return dateFormat.parse(lastAccessTime.toString());
        } catch (Exception e) {
	    System.out.println(e);
        }
        return null;
    }

    private String glueDbName(Table table)
    {
	String glue_prefix = System.getenv("GLUE_PREFIX");
	String glue_dbname = (glue_prefix == null) ? table.getDbName() : glue_prefix + table.getDbName();
	return glue_dbname;
    }
}
