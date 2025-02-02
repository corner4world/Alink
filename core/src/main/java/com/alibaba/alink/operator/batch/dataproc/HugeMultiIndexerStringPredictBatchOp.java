package com.alibaba.alink.operator.batch.dataproc;

import org.apache.flink.api.common.functions.JoinFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.functions.RichGroupReduceFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.api.java.utils.DataSetUtils;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.ml.api.misc.param.Params;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;

import com.alibaba.alink.common.utils.OutputColsHelper;
import com.alibaba.alink.common.utils.TableUtil;
import com.alibaba.alink.operator.batch.BatchOperator;
import com.alibaba.alink.params.dataproc.HugeMultiStringIndexerPredictParams;
import com.alibaba.alink.params.dataproc.MultiStringIndexerPredictParams;
import com.alibaba.alink.params.dataproc.StringIndexerPredictParams;
import com.alibaba.alink.params.shared.colname.HasSelectedCols;

import java.util.Arrays;
import java.util.List;

/**
 * Map string to index based on the model generated by {@link MultiStringIndexerTrainBatchOp}.
 */
public final class HugeMultiIndexerStringPredictBatchOp
	extends BatchOperator <HugeMultiIndexerStringPredictBatchOp>
	implements HugeMultiStringIndexerPredictParams<HugeMultiIndexerStringPredictBatchOp> {

	private static final long serialVersionUID = -1392825675494011436L;

	public HugeMultiIndexerStringPredictBatchOp() {
		this(new Params());
	}

	public HugeMultiIndexerStringPredictBatchOp(Params params) {
		super(params);
	}

	/**
	 * Extract model meta from the model table.
	 *
	 * @param model The model fitted by {@link MultiStringIndexerTrainBatchOp}.
	 * @return A DataSet of only one record, which is the meta string of the model.
	 */
	private DataSet <String> getModelMeta(BatchOperator model) {
		DataSet <Row> modelRows = model.getDataSet();
		return modelRows
			.flatMap(new RichFlatMapFunction <Row, String>() {
				private static final long serialVersionUID = -4936189616000293070L;

				@Override
				public void flatMap(Row row, Collector <String> out) throws Exception {
					long columnIndex = (Long) row.getField(0);
					if (columnIndex < 0L) {
						out.collect((String) row.getField(1));
					}

				}
			})
			.name("get_model_meta")
			.returns(Types.STRING);
	}

	/**
	 * Extract the token to index mapping from the model. The <code>selectedCols</code> should be a subset
	 * of those columns used to train the model.
	 *
	 * @param model        The model fitted by {@link MultiStringIndexerTrainBatchOp}.
	 * @param modelMeta    The meta string of the model.
	 * @param selectedCols The selected columns in prediction data.
	 * @return A DataSet of tuples of column index, token, token index.
	 */
	private DataSet <Tuple3 <Integer, String, Long>> getModelData(BatchOperator model,
																		 DataSet <String> modelMeta,
																		 final String[] selectedCols) {
		DataSet <Row> modelRows = model.getDataSet();
		return modelRows
			.flatMap(new RichFlatMapFunction <Row, Tuple3 <Integer, String, Long>>() {
				private static final long serialVersionUID = 3103936416747450973L;
				transient int[] selectedColIdxInModel;

				@Override
				public void open(Configuration parameters) throws Exception {
					List <String> metaList = getRuntimeContext().getBroadcastVariable("modelMeta");
					if (metaList.size() != 1) {
						throw new IllegalArgumentException("Invalid model.");
					}
					Params meta = Params.fromJson(metaList.get(0));
					String[] trainColNames = meta.get(HasSelectedCols.SELECTED_COLS);
					selectedColIdxInModel = new int[selectedCols.length];
					for (int i = 0; i < selectedCols.length; i++) {
						String selectedColName = selectedCols[i];
						int colIdxInModel = TableUtil.findColIndex(trainColNames, selectedColName);
						if (colIdxInModel < 0) {
							throw new RuntimeException("Can't find col in model: " + selectedColName);
						}
						selectedColIdxInModel[i] = colIdxInModel;
					}
				}

				@Override
				public void flatMap(Row row, Collector <Tuple3 <Integer, String, Long>> out) throws Exception {
					long columnIndex = (Long) row.getField(0);
					if (columnIndex >= 0L) {
						int colIdx = ((Long) row.getField(0)).intValue();
						for (int i = 0; i < selectedColIdxInModel.length; i++) {
							if (selectedColIdxInModel[i] == colIdx) {
								out.collect(Tuple3.of(i, (String) row.getField(1), (Long) row.getField(2)));
								break;
							}
						}
					}
				}
			})
			.withBroadcastSet(modelMeta, "modelMeta")
			.name("get_model_data")
			.returns(new TupleTypeInfo <>(Types.INT, Types.STRING, Types.LONG));
	}

	@Override
	public HugeMultiIndexerStringPredictBatchOp linkFrom(BatchOperator <?>... inputs) {
		Params params = super.getParams();
		BatchOperator model = inputs[0];
		BatchOperator data = inputs[1];

		String[] selectedColNames = params.get(MultiStringIndexerPredictParams.SELECTED_COLS);
		String[] outputColNames = params.get(MultiStringIndexerPredictParams.OUTPUT_COLS);
		if (outputColNames == null) {
			outputColNames = selectedColNames;
		}
		String[] keepColNames = params.get(StringIndexerPredictParams.RESERVED_COLS);
		TypeInformation[] outputColTypes = new TypeInformation[outputColNames.length];
		Arrays.fill(outputColTypes, Types.STRING);

		OutputColsHelper outputColsHelper = new OutputColsHelper(data.getSchema(), outputColNames,
			outputColTypes, keepColNames);

		final int[] selectedColIdx = TableUtil.findColIndicesWithAssertAndHint(data.getSchema(), selectedColNames);
		final HandleInvalid handleInvalidStrategy
			= HandleInvalid
			.valueOf(params.get(StringIndexerPredictParams.HANDLE_INVALID).toString());

		DataSet <Tuple2 <Long, Row>> dataWithId = DataSetUtils.zipWithUniqueId(data.getDataSet());

		DataSet <String> modelMeta = getModelMeta(model);
		DataSet <Tuple3 <Integer, String, Long>> modelData = getModelData(model, modelMeta, selectedColNames);

		// tuple: record id, column index, token
		DataSet <Tuple3 <Long, Integer, Long>> flattened = dataWithId
			.flatMap(new RichFlatMapFunction <Tuple2 <Long, Row>, Tuple3 <Long, Integer, Long>>() {
				private static final long serialVersionUID = 7795878509849151894L;

				@Override
				public void flatMap(Tuple2 <Long, Row> value, Collector <Tuple3 <Long, Integer, Long>> out)
					throws Exception {
					for (int i = 0; i < selectedColIdx.length; i++) {
						Object o = value.f1.getField(selectedColIdx[i]);
						if (o != null) {
							out.collect(Tuple3.of(value.f0, i, (Long) o));
						} else {
							out.collect(Tuple3.of(value.f0, i, -1L));
						}
					}
				}
			})
			.name("flatten_pred_data")
			.returns(new TupleTypeInfo <>(Types.LONG, Types.INT, Types.LONG));

		// record id, column index, token index
		DataSet <Tuple3 <Long, Integer, String>> indexed = flattened
			.leftOuterJoin(modelData)
			.where(1, 2).equalTo(0, 2)
			.with(
				new JoinFunction <Tuple3 <Long, Integer, Long>, Tuple3 <Integer, String, Long>, Tuple3 <Long,
					Integer, String>>() {
					private static final long serialVersionUID = -3177975102816197011L;

					@Override
					public Tuple3 <Long, Integer, String> join(Tuple3 <Long, Integer, Long> first,
															 Tuple3 <Integer, String, Long> second) throws Exception {
						if (second == null) {
							return Tuple3.of(first.f0, first.f1, "null");
						} else {
							return Tuple3.of(first.f0, first.f1, second.f1);
						}
					}
				})
			.name("map_index_to_token")
			.returns(new TupleTypeInfo <>(Types.LONG, Types.INT, Types.STRING));

		// tuple: record id, prediction result
		DataSet <Tuple2 <Long, Row>> aggregateResult = indexed
			.groupBy(0)
			.reduceGroup(new RichGroupReduceFunction <Tuple3 <Long, Integer, String>, Tuple2 <Long, Row>>() {
				private static final long serialVersionUID = 2318140138585310686L;

				@Override
				public void reduce(Iterable <Tuple3 <Long, Integer, String>> values, Collector <Tuple2 <Long, Row>> out)
					throws Exception {

					Long id = null;
					Row r = new Row(selectedColIdx.length);
					for (Tuple3 <Long, Integer, String> v : values) {
						r.setField(v.f1, v.f2);
						id = v.f0;
					}
					out.collect(Tuple2.of(id, r));
				}
			})
			.name("aggregate_result")
			.returns(new TupleTypeInfo <>(Types.LONG, new RowTypeInfo(outputColTypes)));

		DataSet <Row> output = dataWithId
			.join(aggregateResult)
			.where(0).equalTo(0)
			.with(new JoinFunction <Tuple2 <Long, Row>, Tuple2 <Long, Row>, Row>() {
				private static final long serialVersionUID = 3724539437313089427L;

				@Override
				public Row join(Tuple2 <Long, Row> first, Tuple2 <Long, Row> second) throws Exception {
					return outputColsHelper.getResultRow(first.f1, second.f1);
				}
			})
			.name("merge_result")
			.returns(new RowTypeInfo(outputColsHelper.getResultSchema().getFieldTypes()));

		this.setOutput(output, outputColsHelper.getResultSchema());
		return this;
	}
}
