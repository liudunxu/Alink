package com.alibaba.alink.operator.local.sql;

import com.alibaba.alink.operator.batch.source.CsvSourceBatchOp;
import com.alibaba.alink.operator.local.LocalOperator;
import com.alibaba.alink.operator.local.source.TableSourceLocalOp;
import org.junit.Test;

public class UnionLocalOpTest {
	@Test
	public void testUnionLocalOp() {
		String URL = "https://alink-test-data.oss-cn-hangzhou.aliyuncs.com/iris.csv";
		String SCHEMA_STR
			= "sepal_length double, sepal_width double, petal_length double, petal_width double, category string";
		LocalOperator <?> data1 = new TableSourceLocalOp(
			new CsvSourceBatchOp().setFilePath(URL).setSchemaStr(SCHEMA_STR).collectMTable());
		LocalOperator <?> data2 = new TableSourceLocalOp(
			new CsvSourceBatchOp().setFilePath(URL).setSchemaStr(SCHEMA_STR).collectMTable());
		LocalOperator <?> unionOp = new UnionLocalOp();
		unionOp.linkFrom(data1, data2).print();
	}
}