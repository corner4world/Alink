package com.alibaba.alink.common.utils;

import org.apache.flink.types.Row;

import java.util.Arrays;

/**
 * Utils for operations on {@link Row}.
 */
public class RowUtil {

	/*
	 * same toString function as flink 1.9
	 */
	public static String rowToString(Row row) {
		StringBuffer sb = new StringBuffer();
		if (null == row) {
			sb.append("null");
		} else {
			for (int i = 0; i < row.getArity(); i++) {
				if (i > 0) {
					sb.append(",");
				}
				final String arrayString = Arrays.deepToString(new Object[] {row.getField(i)});
				sb.append(arrayString.substring(1, arrayString.length() - 1));
			}
		}
		return sb.toString();
	}

	/**
	 * remove idx value from row.
	 */
	public static Row remove(Row rec, int idx) {
		int n1 = rec.getArity();
		Row ret = new Row(n1 - 1);
		for (int i = 0; i < n1; ++i) {
			if (i < idx) {
				ret.setField(i, rec.getField(i));
			} else if (i > idx) {
				ret.setField(i - 1, rec.getField(i));
			}
		}
		return ret;
	}

	/**
	 * merge row and obj, return a new row.
	 */
	public static Row merge(Row rec1, Object obj) {
		int n1 = rec1.getArity();
		Row ret = new Row(n1 + 1);
		for (int i = 0; i < n1; ++i) {
			ret.setField(i, rec1.getField(i));
		}
		ret.setField(n1, obj);
		return ret;
	}

	/**
	 * merge obj and row, return a new row.
	 */
	public static Row merge(Object obj, Row rec1) {
		int n1 = rec1.getArity();
		Row ret = new Row(n1 + 1);
		ret.setField(0, obj);
		for (int i = 0; i < n1; ++i) {
			ret.setField(i + 1, rec1.getField(i));
		}
		return ret;
	}

	/**
	 * merge left and right.
	 */
	public static Row merge(Row rec1, Row rec2) {
		int n1 = rec1.getArity();
		int n2 = rec2.getArity();
		Row ret = new Row(n1 + n2);
		for (int i = 0; i < n1; ++i) {
			ret.setField(i, rec1.getField(i));
		}
		for (int i = 0; i < n2; ++i) {
			ret.setField(i + n1, rec2.getField(i));
		}
		return ret;
	}

}
