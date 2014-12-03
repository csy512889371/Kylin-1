package com.kylinolap.storage.hbase.coprocessor.endpoint;

import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.kylinolap.cube.CubeSegment;
import com.kylinolap.cube.cuboid.Cuboid;
import com.kylinolap.cube.invertedindex.TableRecord;
import com.kylinolap.cube.invertedindex.TableRecordInfo;
import com.kylinolap.cube.invertedindex.TableRecordInfoDigest;
import com.kylinolap.metadata.model.ColumnDesc;
import com.kylinolap.metadata.model.TableDesc;
import com.kylinolap.metadata.model.cube.CubeDesc;
import com.kylinolap.metadata.model.realization.FunctionDesc;
import com.kylinolap.metadata.model.realization.TblColRef;
import com.kylinolap.storage.StorageContext;
import com.kylinolap.storage.filter.TupleFilter;
import com.kylinolap.storage.hbase.coprocessor.CoprocessorFilter;
import com.kylinolap.storage.hbase.coprocessor.CoprocessorProjector;
import com.kylinolap.storage.hbase.coprocessor.CoprocessorRowType;
import com.kylinolap.storage.hbase.coprocessor.endpoint.generated.IIProtos;
import com.kylinolap.storage.hbase.coprocessor.endpoint.generated.IIProtos.IIResponse.IIRow;
import com.kylinolap.storage.tuple.ITuple;
import com.kylinolap.storage.tuple.ITupleIterator;
import com.kylinolap.storage.tuple.Tuple;
import com.kylinolap.storage.tuple.TupleInfo;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.ipc.BlockingRpcCallback;
import org.apache.hadoop.hbase.ipc.ServerRpcController;

import java.io.IOException;
import java.util.*;

/**
 * Created by Hongbin Ma(Binmahone) on 12/2/14.
 */
public class EndpointTupleIterator implements ITupleIterator {

    private final CubeSegment seg;
    private final TableDesc tableDesc;
    private final TupleFilter rootFilter;
    private final Collection<TblColRef> groupBy;
    private final StorageContext context;
    private final List<FunctionDesc> measures;

    private final List<TblColRef> columns;
    private final List<String> columnNames;
    private final TupleInfo tupleInfo;
    private final TableRecordInfo tableRecordInfo;

    private final CoprocessorRowType pushedDownRowType;
    private final CoprocessorFilter pushedDownFilter;
    private final CoprocessorProjector pushedDownProjector;
    private final EndpointAggregators pushedDownAggregators;

    Iterator<List<IIRow>> regionResponsesIterator = null;
    ITupleIterator tupleIterator = null;

    //TODO  is "dimentsions" useful here?
    public EndpointTupleIterator(CubeSegment cubeSegment, TableDesc tableDesc,
            TupleFilter rootFilter, Collection<TblColRef> groupBy, List<FunctionDesc> measures, StorageContext context, HTableInterface table) throws Throwable {


        this.seg = cubeSegment;
        this.tableDesc = tableDesc;
        this.rootFilter = rootFilter;
        this.groupBy = groupBy;
        this.context = context;
        this.measures = measures;

        this.columns = Lists.newArrayList();
        for (ColumnDesc columnDesc : tableDesc.getColumns()) {
            columns.add(new TblColRef(columnDesc));
        }
        columnNames = getColumnNames(columns);

        this.tupleInfo = buildTupleInfo();
        this.tableRecordInfo = new TableRecordInfo(this.seg);

        this.pushedDownRowType = CoprocessorRowType.fromTableDesc(this.seg, tableDesc);
        this.pushedDownFilter = CoprocessorFilter.fromFilter(this.seg, rootFilter);
        this.pushedDownProjector = CoprocessorProjector.makeForEndpoint(tableRecordInfo, groupBy);
        this.pushedDownAggregators = EndpointAggregators.fromFunctions(measures, tableRecordInfo);

        IIProtos.IIRequest endpointRequest = prepareRequest();
        regionResponsesIterator = getResults(endpointRequest, table);

        if (this.regionResponsesIterator.hasNext()) {
            this.tupleIterator = new SingleRegionTupleIterator(this.regionResponsesIterator.next());
        } else {
            this.tupleIterator = ITupleIterator.EMPTY_TUPLE_ITERATOR;
        }
    }

    @Override
    public boolean hasNext() {
        return this.regionResponsesIterator.hasNext() || this.tupleIterator.hasNext();
    }

    @Override
    public ITuple next() {
        if (!hasNext()) {
            throw new IllegalStateException("No more ITuple in EndpointTupleIterator");
        }

        if (!this.tupleIterator.hasNext()) {
            this.tupleIterator = new SingleRegionTupleIterator(this.regionResponsesIterator.next());
        }
        return this.tupleIterator.next();
    }

    @Override
    public void close() {
    }


    private IIProtos.IIRequest prepareRequest() throws IOException {
        IIProtos.IIRequest request = IIProtos.IIRequest.newBuilder().
                setTableInfo(ByteString.copyFrom(TableRecordInfoDigest.serialize(tableRecordInfo))).
                setType(ByteString.copyFrom(CoprocessorRowType.serialize(pushedDownRowType))).
                setFilter(ByteString.copyFrom(CoprocessorFilter.serialize(pushedDownFilter))).
                setProjector(ByteString.copyFrom(CoprocessorProjector.serialize(pushedDownProjector))).
                setAggregator(ByteString.copyFrom(EndpointAggregators.serialize(pushedDownAggregators))).
                build();

        return request;
    }


    private Iterator<List<IIRow>> getResults(final IIProtos.IIRequest request, HTableInterface table) throws Throwable {
        Map<byte[], List<IIRow>> results = table.coprocessorService(IIProtos.RowsService.class,
                null, null,
                new Batch.Call<IIProtos.RowsService, List<IIRow>>() {
                    public List<IIProtos.IIResponse.IIRow> call(IIProtos.RowsService rowsService) throws IOException {
                        ServerRpcController controller = new ServerRpcController();
                        BlockingRpcCallback<IIProtos.IIResponse> rpcCallback =
                                new BlockingRpcCallback<>();
                        rowsService.getRows(controller, request, rpcCallback);
                        IIProtos.IIResponse response = rpcCallback.get();
                        if (controller.failedOnException()) {
                            throw controller.getFailedOn();
                        }

                        return response.getRowsList();
                    }
                });

        return results.values().iterator();
    }

    private TupleInfo buildTupleInfo() {
        TupleInfo info = new TupleInfo();
        int index = 0;

        for (int i = 0; i < columns.size(); i++) {
            TblColRef column = columns.get(i);

//            if (!dimensions.contains(column)) {
//                continue;
//            }

            info.setField(columnNames.get(i), columns.get(i), columns.get(i).getType().getName(), index++);
        }

        for (FunctionDesc measure : measures) {
            info.setField(measure.getRewriteFieldName(), null, measure.getSQLType(), index++);
        }

        return info;
    }

    private List<String> getColumnNames(List<TblColRef> dimensionColumns) {
        Map<TblColRef, String> aliasMap = context.getAliasMap();
        List<String> result = new ArrayList<String>(dimensionColumns.size());
        for (TblColRef col : dimensionColumns)
            result.add(findName(col, aliasMap));
        return result;
    }

    private String findName(TblColRef column, Map<TblColRef, String> aliasMap) {
        String name = null;
        if (aliasMap != null) {
            name = aliasMap.get(column);
        }
        if (name == null) {
            name = column.getName();
        }
        return name;

    }


    /**
     * Internal class to handle iterators for a single region's returned rows
     */
    class SingleRegionTupleIterator implements ITupleIterator {
        private List<IIRow> rows;
        private int index = 0;

        //not thread safe!
        private TableRecord tableRecord;
        private List<String> measureValues;
        private Tuple tuple;

        public SingleRegionTupleIterator(List<IIProtos.IIResponse.IIRow> rows) {
            this.rows = rows;
            this.index = 0;
            this.tableRecord = new TableRecord(tableRecordInfo);
            this.tuple = new Tuple(tupleInfo);
        }

        @Override
        public boolean hasNext() {
            return index < rows.size();
        }

        @Override
        public ITuple next() {
            if (!hasNext()) {
                throw new IllegalStateException("No more Tuple in the SingleRegionTupleIterator");
            }

            IIRow currentRow = rows.get(index);
            //ByteBuffer columnsBuffer = currentRow.getColumns().asReadOnlyByteBuffer();//avoid creating byte[], if possible
            //this.tableRecord.setBytes(columnsBuffer.array(), columnsBuffer.position(), columnsBuffer.limit());
            byte[] columnsBytes = currentRow.getColumns().toByteArray();
            this.tableRecord.setBytes(columnsBytes, 0, columnsBytes.length);

//            ByteBuffer measuresBuffer = currentRow.getMeasures().asReadOnlyByteBuffer();
//            this.measureValues = pushedDownAggregators.deserializeMetricValues(measuresBuffer.array(), measuresBuffer.position());
            byte[] measuresBytes = currentRow.getMeasures().toByteArray();
            this.measureValues = pushedDownAggregators.deserializeMetricValues(measuresBytes, 0);

            index++;

            return makeTuple(this.tableRecord, this.measureValues);
        }

        @Override
        public void close() {

        }

        private ITuple makeTuple(TableRecord tableRecord, List<String> measureValues) {
            // groups
            List<String> columnValues = tableRecord.getValueList();
            for (int i = 0; i < columnNames.size(); i++) {
                TblColRef column = columns.get(i);
                if (!tuple.hasColumn(column)) {
                    continue;
                }
                tuple.setValue(columnNames.get(i), columnValues.get(i));
            }

            for (int i = 0; i < measures.size(); ++i) {
                tuple.setValue(measures.get(i).getRewriteFieldName(), measureValues.get(i));
            }
            return tuple;
        }


    }
}